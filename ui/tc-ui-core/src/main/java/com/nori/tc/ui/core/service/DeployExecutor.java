package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import com.nori.tc.ui.core.deploy.DeployResolvedStepPlan;
import com.nori.tc.ui.core.deploy.DeployStepStatus;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.port.db.DeployStorePort;
import com.nori.tc.ui.core.port.redis.DeployExecutionCoordinationPort;
import com.nori.tc.ui.core.properties.UiDeployProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Deploy target 실행 책임을 분리합니다.
 */
@Service
public class DeployExecutor {

    private final DeployStorePort deployStorePort;
    private final DeployCommandAssembler commandAssembler;
    private final DeployTraceRecorder deployTraceRecorder;
    private final DeployRollbackCoordinator rollbackCoordinator;
    private final DeployExecutionCoordinationPort deployExecutionCoordinationPort;
    private final UiDeployProperties deployProperties;

    private static final long LEASE_ACQUIRE_POLL_MS = 50L;

    public DeployExecutor(
            final DeployStorePort deployStorePort,
            final DeployCommandAssembler commandAssembler,
            final DeployTraceRecorder deployTraceRecorder,
            final DeployRollbackCoordinator rollbackCoordinator,
            final DeployExecutionCoordinationPort deployExecutionCoordinationPort,
            final UiDeployProperties deployProperties
    ) {
        this.deployStorePort = Objects.requireNonNull(deployStorePort, "deployStorePort is null");
        this.commandAssembler = Objects.requireNonNull(commandAssembler, "commandAssembler is null");
        this.deployTraceRecorder = Objects.requireNonNull(deployTraceRecorder, "deployTraceRecorder is null");
        this.rollbackCoordinator = Objects.requireNonNull(rollbackCoordinator, "rollbackCoordinator is null");
        this.deployExecutionCoordinationPort = Objects.requireNonNull(
                deployExecutionCoordinationPort,
                "deployExecutionCoordinationPort is null"
        );
        this.deployProperties = Objects.requireNonNull(deployProperties, "deployProperties is null");
    }

    public void executeTarget(
            final long deployKey,
            final UiCommandEventType deployEventType,
            final PersistedDeployTarget target,
            final String actor
    ) {
        final String eqpId = target.planDraft().eqpId();
        final String executionKey = buildExecutionKey(deployKey, target.deployTargetKey());
        final long leaseTtlMs = resolveLeaseTtlMs(target);
        final long executionTtlMs = Math.max(1L, deployProperties.summaryCacheTtlSeconds() * 1_000L);

        try {
            if (!awaitTargetLease(eqpId, executionKey, leaseTtlMs)) {
                handleTargetFailure(
                        deployKey,
                        target,
                        List.of(),
                        null,
                        "TARGET_LEASE_TIMEOUT",
                        "다른 인스턴스에서 동일 설비 deploy를 처리 중입니다.",
                        actor
                );
                return;
            }
            if (!deployExecutionCoordinationPort.registerTargetExecution(executionKey, executionTtlMs)) {
                commandAssembler.refreshDeployStatus(deployKey, null, null);
                return;
            }
            doExecuteTarget(deployKey, deployEventType, target, actor);
        } finally {
            try {
                commandAssembler.refreshDeployStatus(deployKey, null, null);
            } finally {
                deployExecutionCoordinationPort.releaseTargetLease(eqpId, executionKey);
            }
        }
    }

    private void doExecuteTarget(
            final long deployKey,
            final UiCommandEventType deployEventType,
            final PersistedDeployTarget target,
            final String actor
    ) {
        DeployResolvedStepPlan currentStep = null;
        final List<DeployResolvedStepPlan> completedSteps = new ArrayList<>();
        final JsonNode targetPreState = commandAssembler.captureTargetPreState(target).orElse(null);

        try {
            for (DeployResolvedStepPlan step : target.planDraft().resolvedSteps()) {
                currentStep = step;
                if (step.rollbackEnabled()) {
                    commandAssembler.saveCompensationSnapshot(target, step, targetPreState);
                }

                final String currentTraceId = commandAssembler.buildTraceId(
                        deployKey,
                        target.deployTargetKey(),
                        step.code(),
                        1
                );
                final OffsetDateTime stepStartedAt = OffsetDateTime.now(ZoneOffset.UTC);
                final OffsetDateTime stepTimeoutAt = stepStartedAt.plus(Duration.ofMillis(step.timeoutMs()));
                deployStorePort.markTargetWaitingReply(
                        target.deployTargetKey(),
                        step.order(),
                        step.code(),
                        1,
                        currentTraceId,
                        stepStartedAt,
                        stepTimeoutAt
                );

                final Set<String> expectedSources = commandAssembler.expectedSources(step);
                final var replyFuture = deployTraceRecorder.register(currentTraceId, expectedSources, step.timeoutMs());

                try {
                    commandAssembler.publishStepCommand(
                            deployKey,
                            deployEventType,
                            target,
                            step,
                            currentTraceId,
                            actor
                    );
                } catch (Exception publishException) {
                    deployTraceRecorder.cancel(currentTraceId);
                    handleTargetFailure(
                            deployKey,
                            target,
                            completedSteps,
                            step,
                            "PUBLISH_FAILED",
                            publishException.getMessage() == null
                                    ? "Kafka 발행 중 오류가 발생했습니다."
                                    : publishException.getMessage(),
                            actor
                    );
                    return;
                }

                try {
                    final var completion = replyFuture.get(step.timeoutMs(), TimeUnit.MILLISECONDS);
                    if (!completion.success()) {
                        handleTargetFailure(
                                deployKey,
                                target,
                                completedSteps,
                                step,
                                completion.firstErrorCode(),
                                completion.firstErrorMessage(),
                                actor
                        );
                        return;
                    }
                } catch (TimeoutException timeoutException) {
                    deployTraceRecorder.cancel(currentTraceId);
                    handleTargetFailure(
                            deployKey,
                            target,
                            completedSteps,
                            step,
                            "STEP_TIMEOUT",
                            "step 응답 대기 시간이 초과되었습니다.",
                            actor
                    );
                    return;
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    deployTraceRecorder.cancel(currentTraceId);
                    handleTargetFailure(
                            deployKey,
                            target,
                            completedSteps,
                            step,
                            "STEP_INTERRUPTED",
                            "step 실행 대기 중 인터럽트가 발생했습니다.",
                            actor
                    );
                    return;
                } catch (ExecutionException executionException) {
                    deployTraceRecorder.cancel(currentTraceId);
                    final Throwable cause = executionException.getCause() == null
                            ? executionException
                            : executionException.getCause();
                    handleTargetFailure(
                            deployKey,
                            target,
                            completedSteps,
                            step,
                            "STEP_EXECUTION_FAILED",
                            cause.getMessage() == null
                                    ? "step 응답 처리 중 오류가 발생했습니다."
                                    : cause.getMessage(),
                            actor
                    );
                    return;
                }
                completedSteps.add(step);
            }

            deployStorePort.markTargetCompleted(
                    target.deployTargetKey(),
                    OffsetDateTime.now(ZoneOffset.UTC)
            );
        } catch (Exception unexpectedException) {
            handleTargetFailure(
                    deployKey,
                    target,
                    completedSteps,
                    currentStep,
                    "UNEXPECTED_ERROR",
                    unexpectedException.getMessage() == null
                            ? "target 실행 중 내부 오류가 발생했습니다."
                            : unexpectedException.getMessage(),
                    actor
            );
        }
    }

    private void handleTargetFailure(
            final long deployKey,
            final PersistedDeployTarget target,
            final List<DeployResolvedStepPlan> completedSteps,
            final DeployResolvedStepPlan failedStep,
            final String errorCode,
            final String errorMessage,
            final String actor
    ) {
        final List<DeployResolvedStepPlan> compensatableSteps = completedSteps.stream()
                .filter(DeployResolvedStepPlan::rollbackEnabled)
                .toList();

        if (compensatableSteps.isEmpty()) {
            deployStorePort.markTargetFailed(
                    target.deployTargetKey(),
                    failedStep == null ? 0 : failedStep.order(),
                    failedStep == null ? null : failedStep.code(),
                    DeployStepStatus.FAILED,
                    commandAssembler.normalizeOptionalText(errorCode),
                    commandAssembler.normalizeOptionalText(errorMessage),
                    false,
                    OffsetDateTime.now(ZoneOffset.UTC)
            );
            commandAssembler.refreshDeployStatus(deployKey, errorCode, errorMessage);
            return;
        }

        rollbackCoordinator.rollbackCompletedSteps(
                deployKey,
                target,
                compensatableSteps,
                commandAssembler.normalizeOptionalText(errorCode),
                commandAssembler.normalizeOptionalText(errorMessage),
                actor
        );
        commandAssembler.refreshDeployStatus(deployKey, errorCode, errorMessage);
    }

    private boolean awaitTargetLease(
            final String eqpId,
            final String ownerToken,
            final long ttlMs
    ) {
        final long deadlineEpochMs = System.currentTimeMillis() + ttlMs;
        while (System.currentTimeMillis() <= deadlineEpochMs) {
            if (deployExecutionCoordinationPort.tryAcquireTargetLease(eqpId, ownerToken, ttlMs)) {
                return true;
            }
            try {
                Thread.sleep(LEASE_ACQUIRE_POLL_MS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private long resolveLeaseTtlMs(final PersistedDeployTarget target) {
        long totalTimeoutMs = 0L;
        for (DeployResolvedStepPlan step : target.planDraft().resolvedSteps()) {
            totalTimeoutMs += Math.max(0L, step.timeoutMs());
        }
        return Math.max(LEASE_ACQUIRE_POLL_MS,
                totalTimeoutMs + Math.max(0L, deployProperties.targetLockExtraTtlMs()));
    }

    private String buildExecutionKey(final long deployKey, final long deployTargetKey) {
        return "DEPLOY-EXEC:" + deployKey + ":" + deployTargetKey;
    }
}
