package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nori.tc.common.runtime.support.ExecutorShutdownResult;
import com.nori.tc.common.runtime.support.ExecutorShutdownSupport;
import com.nori.tc.common.runtime.support.RuntimeLogSummary;
import com.nori.tc.messaging.kafka.contract.GatewayEquipmentProfileSnapshot;
import com.nori.tc.ui.core.deploy.DeployCreateResult;
import com.nori.tc.ui.core.deploy.DeployPlanCommand;
import com.nori.tc.ui.core.deploy.DeployPlanningResult;
import com.nori.tc.ui.core.deploy.DeployRollbackMode;
import com.nori.tc.ui.core.deploy.DeployResolvedStepPlan;
import com.nori.tc.ui.core.deploy.DeployResultMessage;
import com.nori.tc.ui.core.deploy.DeployResultSeverity;
import com.nori.tc.ui.core.deploy.DeploySnapshotKind;
import com.nori.tc.ui.core.deploy.DeploySnapshotScope;
import com.nori.tc.ui.core.deploy.DeployStatus;
import com.nori.tc.ui.core.deploy.DeployStepStatus;
import com.nori.tc.ui.core.deploy.DeployStoredSnapshot;
import com.nori.tc.ui.core.deploy.DeploySummarySnapshot;
import com.nori.tc.ui.core.deploy.DeploySummaryView;
import com.nori.tc.ui.core.deploy.DeployTargetCounts;
import com.nori.tc.ui.core.deploy.DeployTargetPage;
import com.nori.tc.ui.core.deploy.DeployTargetStatus;
import com.nori.tc.ui.core.deploy.DeployTraceCompletion;
import com.nori.tc.ui.core.deploy.PersistedDeploy;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.port.db.DeployStorePort;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import com.nori.tc.ui.core.port.messaging.UiBusinessEventPublishPort;
import com.nori.tc.ui.core.port.messaging.UiGatewayEventPublishPort;
import com.nori.tc.ui.core.properties.UiDeployProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
/**
 * deploy 생성 / 실행 / polling 조회를 담당합니다.
 */
@Service
public class DeployService {
    private static final Logger log = LoggerFactory.getLogger(DeployService.class);
    private static final int MAX_TARGET_PAGE_SIZE = 200;
    private static final long TARGET_EXECUTOR_SHUTDOWN_WAIT_MS = 5_000L;
    private static final String TARGET_EXECUTOR_THREAD_PREFIX = "ui-deploy-target-";

    private final DeployPlanningService deployPlanningService;
    private final DeployStorePort deployStorePort;
    private final DeployTraceRegistry deployTraceRegistry;
    private final DeployServiceCommandSupport commandSupport;
    private final ExecutorService targetExecutor;
    private final ConcurrentHashMap<String, ReentrantLock> eqpExecutionLocks;
    private final AtomicInteger targetExecutorThreadSequence = new AtomicInteger(0);

    public DeployService(
            final UiDeployProperties deployProperties,
            final DeployPlanningService deployPlanningService,
            final DeployStorePort deployStorePort,
            final EqpCrudPort eqpCrudPort,
            final UiGatewayEventPublishPort gatewayEventPublishPort,
            final UiBusinessEventPublishPort businessEventPublishPort,
            final DeployTraceRegistry deployTraceRegistry,
            final ObjectMapper objectMapper
    ) {
        final UiDeployProperties validatedDeployProperties =
                Objects.requireNonNull(deployProperties, "deployProperties is null");
        this.deployPlanningService = Objects.requireNonNull(deployPlanningService, "deployPlanningService is null");
        this.deployStorePort = Objects.requireNonNull(deployStorePort, "deployStorePort is null");
        Objects.requireNonNull(eqpCrudPort, "eqpCrudPort is null");
        Objects.requireNonNull(gatewayEventPublishPort, "gatewayEventPublishPort is null");
        Objects.requireNonNull(businessEventPublishPort, "businessEventPublishPort is null");
        this.deployTraceRegistry = Objects.requireNonNull(deployTraceRegistry, "deployTraceRegistry is null");
        Objects.requireNonNull(objectMapper, "objectMapper is null");
        this.commandSupport = new DeployServiceCommandSupport(
                deployStorePort,
                eqpCrudPort,
                gatewayEventPublishPort,
                businessEventPublishPort,
                objectMapper
        );
        this.targetExecutor = Executors.newFixedThreadPool(
                Math.max(1, validatedDeployProperties.targetConcurrency()),
                commandSupport.namedThreadFactory(TARGET_EXECUTOR_THREAD_PREFIX, targetExecutorThreadSequence)
        );
        this.eqpExecutionLocks = new ConcurrentHashMap<>();
        log.info("DeployService target executor initialized. {}",
                RuntimeLogSummary.kv(
                        "targetConcurrency", validatedDeployProperties.targetConcurrency(),
                        "threadPrefix", TARGET_EXECUTOR_THREAD_PREFIX
                ));
    }

    /**
     * deploy를 생성하고 background execution을 시작합니다.
     *
     * @param command deploy 입력
     * @param actor 요청 사용자
     * @return 생성 결과
     */
    public DeployCreateResult createDeploy(
            final DeployPlanCommand command,
            final String actor
    ) {
        final DeployPlanningResult planningResult = deployPlanningService.plan(command);
        final PersistedDeploy persistedDeploy = deployStorePort.createDeploy(
                command,
                planningResult,
                commandSupport.normalizeActor(actor)
        );
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        final List<PersistedDeployTarget> executableTargets = persistedDeploy.targets().stream()
                .filter(target -> !target.planDraft().noChange())
                .toList();
        if (executableTargets.isEmpty()) {
            deployStorePort.updateDeployStatus(
                    persistedDeploy.deployKey(),
                    DeployStatus.COMPLETED,
                    now,
                    now,
                    null,
                    null
            );
            return new DeployCreateResult(
                    persistedDeploy.deployKey(),
                    DeployStatus.COMPLETED,
                    "DEPLOY_COMPLETED",
                    commandSupport.completedMessage(new DeployTargetCounts(
                            persistedDeploy.targets().size(),
                            0,
                            persistedDeploy.targets().size(),
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0
                    ))
            );
        }
        deployStorePort.updateDeployStatus(
                persistedDeploy.deployKey(),
                DeployStatus.RUNNING,
                now,
                null,
                null,
                null
        );
        executableTargets.forEach(target -> targetExecutor.submit(() -> executeTarget(
                persistedDeploy.deployKey(),
                planningResult.eventType(),
                target,
                commandSupport.normalizeActor(actor)
        )));
        return new DeployCreateResult(
                persistedDeploy.deployKey(),
                DeployStatus.RUNNING,
                "DEPLOY_ACCEPTED",
                new DeployResultMessage(
                        "작업이 접수되었습니다.",
                        "설비 " + persistedDeploy.targets().size() + "대에 대한 작업이 등록되었습니다.",
                        DeployResultSeverity.INFO
                )
        );
    }

    /**
     * deploy summary polling 결과를 조회합니다.
     *
     * @param deployKey deploy key
     * @return summary view
     */
    public DeploySummaryView getDeploySummary(final long deployKey) {
        final DeploySummarySnapshot snapshot = deployStorePort.findDeploySummary(deployKey)
                .orElseThrow(() -> new UiNotFoundException("deploy를 찾을 수 없습니다. deployKey=" + deployKey));

        final boolean resolvedTerminal = commandSupport.isTerminal(snapshot.deployStatus());
        final String resultCode = commandSupport.resolveResultCode(snapshot.deployStatus(), snapshot.targetSummary());
        final DeployResultMessage resultMessage = commandSupport.resolveSummaryMessage(snapshot.deployStatus(), snapshot.targetSummary());

        return new DeploySummaryView(
                snapshot.deployKey(),
                snapshot.deployName(),
                snapshot.eventType(),
                snapshot.deployStatus(),
                snapshot.executionPolicy(),
                snapshot.createdAt(),
                snapshot.startedAt(),
                snapshot.finishedAt(),
                snapshot.targetSummary(),
                resolvedTerminal,
                resolvedTerminal,
                resultCode,
                resultMessage
        );
    }

    /**
     * deploy target 페이지를 조회합니다.
     *
     * @param deployKey deploy key
     * @param page page index
     * @param size page size
     * @param statusFilter 상태 필터
     * @param eqpIdFilter eqpId 필터
     * @return target page
     */
    public DeployTargetPage getDeployTargets(
            final long deployKey,
            final int page,
            final int size,
            final String statusFilter,
            final String eqpIdFilter
    ) {
        deployStorePort.findDeploySummary(deployKey)
                .orElseThrow(() -> new UiNotFoundException("deploy를 찾을 수 없습니다. deployKey=" + deployKey));

        if (page < 0) {
            throw new UiBadRequestException("page는 0 이상이어야 합니다.");
        }
        if (size <= 0) {
            throw new UiBadRequestException("size는 1 이상이어야 합니다.");
        }
        if (size > MAX_TARGET_PAGE_SIZE) {
            throw new UiBadRequestException("size는 " + MAX_TARGET_PAGE_SIZE + " 이하여야 합니다.");
        }

        final DeployTargetStatus targetStatus = commandSupport.parseTargetStatus(statusFilter);
        return deployStorePort.findDeployTargets(
                deployKey,
                page,
                size,
                targetStatus,
                commandSupport.normalizeOptionalText(eqpIdFilter)
        );
    }

    /**
     * tc.ui.commands reply를 deploy trace에 반영합니다.
     *
     * @param reply 수신 reply
     * @return deploy trace 처리 여부
     */
    public boolean handleReply(final UiCommandReply reply) {
        return deployTraceRegistry.handle(reply);
    }

    @PreDestroy
    public void shutdown() {
        final ExecutorShutdownResult result = ExecutorShutdownSupport.shutdown(
                targetExecutor,
                TARGET_EXECUTOR_SHUTDOWN_WAIT_MS
        );
        log.info("DeployService target executor stopped. {}",
                RuntimeLogSummary.kv(
                        "terminated", result.terminated(),
                        "forced", result.forced(),
                        "interrupted", result.interrupted(),
                        "droppedTasks", result.droppedTaskCount(),
                        "waitMs", result.waitMs(),
                        "activeEqpLocks", eqpExecutionLocks.size()
                ));
    }

    private void executeTarget(
            final long deployKey,
            final UiCommandEventType deployEventType,
            final PersistedDeployTarget target,
            final String actor
    ) {
        final ReentrantLock lock = eqpExecutionLocks.computeIfAbsent(
                target.planDraft().eqpId(),
                ignored -> new ReentrantLock()
        );

        lock.lock();
        try {
            doExecuteTarget(deployKey, deployEventType, target, actor);
        } finally {
            try {
                commandSupport.refreshDeployStatus(deployKey, null, null);
            } finally {
                lock.unlock();
                cleanupEqpLock(target.planDraft().eqpId(), lock);
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
        final JsonNode targetPreState = commandSupport.captureTargetPreState(target);

        try {
            for (DeployResolvedStepPlan step : target.planDraft().resolvedSteps()) {
                currentStep = step;
                if (step.rollbackEnabled()) {
                    commandSupport.saveCompensationSnapshot(target, step, targetPreState);
                }

                final String currentTraceId = commandSupport.buildTraceId(
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

                final Set<String> expectedSources = commandSupport.expectedSources(step);
                final var replyFuture = deployTraceRegistry.register(currentTraceId, expectedSources);

                try {
                    commandSupport.publishStepCommand(
                            deployKey,
                            deployEventType,
                            target,
                            step,
                            currentTraceId,
                            actor
                    );
                } catch (Exception publishException) {
                    deployTraceRegistry.cancel(currentTraceId);
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
                    final DeployTraceCompletion completion = replyFuture.get(step.timeoutMs(), TimeUnit.MILLISECONDS);
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
                    deployTraceRegistry.cancel(currentTraceId);
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
                    deployTraceRegistry.cancel(currentTraceId);
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
                    deployTraceRegistry.cancel(currentTraceId);
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
            final String errorCode = "UNEXPECTED_ERROR";
            final String errorMessage = unexpectedException.getMessage() == null
                    ? "target 실행 중 내부 오류가 발생했습니다."
                    : unexpectedException.getMessage();
            handleTargetFailure(
                    deployKey,
                    target,
                    completedSteps,
                    currentStep,
                    errorCode,
                    errorMessage,
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
                    commandSupport.normalizeOptionalText(errorCode),
                    commandSupport.normalizeOptionalText(errorMessage),
                    false,
                    OffsetDateTime.now(ZoneOffset.UTC)
            );
            commandSupport.refreshDeployStatus(deployKey, errorCode, errorMessage);
            return;
        }

        rollbackCompletedSteps(
                deployKey,
                target,
                compensatableSteps,
                commandSupport.normalizeOptionalText(errorCode),
                commandSupport.normalizeOptionalText(errorMessage),
                actor
        );
        commandSupport.refreshDeployStatus(deployKey, errorCode, errorMessage);
    }

    private void rollbackCompletedSteps(
            final long deployKey,
            final PersistedDeployTarget target,
            final List<DeployResolvedStepPlan> completedSteps,
            final String originalErrorCode,
            final String originalErrorMessage,
            final String actor
    ) {
        for (int index = completedSteps.size() - 1; index >= 0; index--) {
            final DeployResolvedStepPlan step = completedSteps.get(index);
            if (!step.rollbackEnabled()) {
                continue;
            }
            if (step.rollbackMode() == DeployRollbackMode.MANUAL_ONLY || step.rollbackAction() == null) {
                deployStorePort.markTargetCompensationFailed(
                        target.deployTargetKey(),
                        step.order(),
                        step.code(),
                        "MANUAL_ROLLBACK_REQUIRED",
                        "수동 rollback 이 필요한 step 입니다. stepCode=" + step.code(),
                        OffsetDateTime.now(ZoneOffset.UTC)
                );
                return;
            }

            final DeployStoredSnapshot compensationSnapshot = deployStorePort.findLatestTargetSnapshot(
                            target.deployTargetKey(),
                            DeploySnapshotScope.STEP_PRE_STATE,
                            step.code(),
                            DeploySnapshotKind.COMPENSATION_COMMAND
                    )
                    .orElse(null);
            if (compensationSnapshot == null) {
                deployStorePort.markTargetCompensationFailed(
                        target.deployTargetKey(),
                        step.order(),
                        step.code(),
                        "COMPENSATION_SNAPSHOT_NOT_FOUND",
                        "보상 실행에 필요한 step snapshot을 찾을 수 없습니다. stepCode=" + step.code(),
                        OffsetDateTime.now(ZoneOffset.UTC)
                );
                return;
            }

            final JsonNode snapshotData = compensationSnapshot.snapshotData();
            if (snapshotData.path("skip").asBoolean(false)) {
                continue;
            }

            final UiCommandEventType compensationEventType;
            final String compensationInterfaceType;
            final JsonNode compensationUiMessage;
            final GatewayEquipmentProfileSnapshot equipmentProfile;
            try {
                compensationEventType = commandSupport.parseCompensationEventType(
                        DeployServiceCommandSupport.textValue(snapshotData, "eventType")
                );
                compensationInterfaceType = DeployServiceCommandSupport.textValue(snapshotData, "interfaceType");
                compensationUiMessage = snapshotData.path("uiMessage");
                equipmentProfile = commandSupport.parseEquipmentProfile(snapshotData.get("equipmentProfile"));
            } catch (Exception exception) {
                deployStorePort.markTargetCompensationFailed(
                        target.deployTargetKey(),
                        step.order(),
                        step.code(),
                        "COMPENSATION_SNAPSHOT_INVALID",
                        exception.getMessage() == null
                                ? "보상 snapshot 해석 중 오류가 발생했습니다."
                                : exception.getMessage(),
                        OffsetDateTime.now(ZoneOffset.UTC)
                );
                return;
            }
            final String compensationTraceId = commandSupport.buildTraceId(
                    deployKey,
                    target.deployTargetKey(),
                    "RB-" + step.code(),
                    1
            );
            final long timeoutMs = snapshotData.path("timeoutMs").asLong(step.timeoutMs());
            final OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
            final OffsetDateTime timeoutAt = startedAt.plus(Duration.ofMillis(timeoutMs));
            deployStorePort.markTargetRollingBack(
                    target.deployTargetKey(),
                    step.order(),
                    step.code(),
                    1,
                    compensationTraceId,
                    originalErrorCode,
                    originalErrorMessage,
                    startedAt,
                    timeoutAt
            );

            final Set<String> expectedSources = commandSupport.expectedSources(step.targetApp());
            final var replyFuture = deployTraceRegistry.register(compensationTraceId, expectedSources);

            try {
                commandSupport.publishCommand(
                        deployKey,
                        step.targetApp(),
                        compensationEventType,
                        target,
                        compensationInterfaceType,
                        compensationUiMessage,
                        equipmentProfile,
                        compensationTraceId,
                        actor
                );
            } catch (Exception publishException) {
                deployTraceRegistry.cancel(compensationTraceId);
                deployStorePort.markTargetCompensationFailed(
                        target.deployTargetKey(),
                        step.order(),
                        step.code(),
                        "COMPENSATION_PUBLISH_FAILED",
                        publishException.getMessage() == null
                                ? "보상 Kafka 발행 중 오류가 발생했습니다."
                                : publishException.getMessage(),
                        OffsetDateTime.now(ZoneOffset.UTC)
                );
                return;
            }

            final DeployTraceCompletion completion;
            try {
                completion = replyFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeoutException) {
                deployTraceRegistry.cancel(compensationTraceId);
                deployStorePort.markTargetCompensationFailed(
                        target.deployTargetKey(),
                        step.order(),
                        step.code(),
                        "COMPENSATION_TIMEOUT",
                        "보상 step 응답 대기 시간이 초과되었습니다.",
                        OffsetDateTime.now(ZoneOffset.UTC)
                );
                return;
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                deployTraceRegistry.cancel(compensationTraceId);
                deployStorePort.markTargetCompensationFailed(
                        target.deployTargetKey(),
                        step.order(),
                        step.code(),
                        "COMPENSATION_INTERRUPTED",
                        "보상 step 실행 대기 중 인터럽트가 발생했습니다.",
                        OffsetDateTime.now(ZoneOffset.UTC)
                );
                return;
            } catch (ExecutionException executionException) {
                deployTraceRegistry.cancel(compensationTraceId);
                final Throwable cause = executionException.getCause() == null
                        ? executionException
                        : executionException.getCause();
                deployStorePort.markTargetCompensationFailed(
                        target.deployTargetKey(),
                        step.order(),
                        step.code(),
                        "COMPENSATION_EXECUTION_FAILED",
                        cause.getMessage() == null
                                ? "보상 step 응답 처리 중 오류가 발생했습니다."
                                : cause.getMessage(),
                        OffsetDateTime.now(ZoneOffset.UTC)
                );
                return;
            }

            if (!completion.success()) {
                deployStorePort.markTargetCompensationFailed(
                        target.deployTargetKey(),
                        step.order(),
                        step.code(),
                        commandSupport.normalizeOptionalText(completion.firstErrorCode()),
                        commandSupport.normalizeOptionalText(completion.firstErrorMessage()),
                        OffsetDateTime.now(ZoneOffset.UTC)
                );
                return;
            }
        }

        deployStorePort.markTargetRolledBack(
                target.deployTargetKey(),
                originalErrorCode,
                originalErrorMessage,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    private void cleanupEqpLock(
            final String eqpId,
            final ReentrantLock lock
    ) {
        if (lock.isLocked() || lock.hasQueuedThreads()) {
            return;
        }
        eqpExecutionLocks.remove(eqpId, lock);
    }
}
