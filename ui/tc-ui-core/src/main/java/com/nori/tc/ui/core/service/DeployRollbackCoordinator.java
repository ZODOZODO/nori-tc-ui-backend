package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import com.nori.tc.messaging.kafka.contract.GatewayEquipmentProfileSnapshot;
import com.nori.tc.ui.core.deploy.DeployResolvedStepPlan;
import com.nori.tc.ui.core.deploy.DeployRollbackMode;
import com.nori.tc.ui.core.deploy.DeploySnapshotKind;
import com.nori.tc.ui.core.deploy.DeploySnapshotScope;
import com.nori.tc.ui.core.deploy.DeployStoredSnapshot;
import com.nori.tc.ui.core.port.db.DeployStorePort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Deploy rollback 책임을 분리합니다.
 */
@Service
public class DeployRollbackCoordinator {

    private final DeployStorePort deployStorePort;
    private final DeployCommandAssembler commandAssembler;
    private final DeployTraceRecorder deployTraceRecorder;

    public DeployRollbackCoordinator(
            final DeployStorePort deployStorePort,
            final DeployCommandAssembler commandAssembler,
            final DeployTraceRecorder deployTraceRecorder
    ) {
        this.deployStorePort = Objects.requireNonNull(deployStorePort, "deployStorePort is null");
        this.commandAssembler = Objects.requireNonNull(commandAssembler, "commandAssembler is null");
        this.deployTraceRecorder = Objects.requireNonNull(deployTraceRecorder, "deployTraceRecorder is null");
    }

    public void rollbackCompletedSteps(
            final long deployKey,
            final com.nori.tc.ui.core.deploy.PersistedDeployTarget target,
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

            final com.nori.tc.ui.core.model.UiCommandEventType compensationEventType;
            final String compensationInterfaceType;
            final JsonNode compensationUiMessage;
            final GatewayEquipmentProfileSnapshot equipmentProfile;
            try {
                compensationEventType = commandAssembler.parseCompensationEventType(
                        DeployServiceCommandSupport.textValue(snapshotData, "eventType")
                );
                compensationInterfaceType = DeployServiceCommandSupport.textValue(snapshotData, "interfaceType");
                compensationUiMessage = snapshotData.path("uiMessage");
                equipmentProfile = commandAssembler.parseEquipmentProfile(snapshotData.get("equipmentProfile"));
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

            final String compensationTraceId = commandAssembler.buildTraceId(
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

            final Set<String> expectedSources = commandAssembler.expectedSources(step.targetApp());
            final var replyFuture = deployTraceRecorder.register(compensationTraceId, expectedSources);

            try {
                commandAssembler.publishCommand(
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
                deployTraceRecorder.cancel(compensationTraceId);
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

            final Optional<com.nori.tc.ui.core.deploy.DeployTraceCompletion> completionOptional =
                    waitCompletion(replyFuture, compensationTraceId, target.deployTargetKey(), step, timeoutMs);
            if (completionOptional.isEmpty()) {
                return;
            }
            final com.nori.tc.ui.core.deploy.DeployTraceCompletion completion = completionOptional.get();

            if (!completion.success()) {
                deployStorePort.markTargetCompensationFailed(
                        target.deployTargetKey(),
                        step.order(),
                        step.code(),
                        commandAssembler.normalizeOptionalText(completion.firstErrorCode()),
                        commandAssembler.normalizeOptionalText(completion.firstErrorMessage()),
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

    private Optional<com.nori.tc.ui.core.deploy.DeployTraceCompletion> waitCompletion(
            final java.util.concurrent.CompletableFuture<com.nori.tc.ui.core.deploy.DeployTraceCompletion> replyFuture,
            final String compensationTraceId,
            final long deployTargetKey,
            final DeployResolvedStepPlan step,
            final long timeoutMs
    ) {
        try {
            return Optional.of(replyFuture.get(timeoutMs, TimeUnit.MILLISECONDS));
        } catch (TimeoutException timeoutException) {
            deployTraceRecorder.cancel(compensationTraceId);
            deployStorePort.markTargetCompensationFailed(
                    deployTargetKey,
                    step.order(),
                    step.code(),
                    "COMPENSATION_TIMEOUT",
                    "보상 step 응답 대기 시간이 초과되었습니다.",
                    OffsetDateTime.now(ZoneOffset.UTC)
            );
            return Optional.empty();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            deployTraceRecorder.cancel(compensationTraceId);
            deployStorePort.markTargetCompensationFailed(
                    deployTargetKey,
                    step.order(),
                    step.code(),
                    "COMPENSATION_INTERRUPTED",
                    "보상 step 실행 대기 중 인터럽트가 발생했습니다.",
                    OffsetDateTime.now(ZoneOffset.UTC)
            );
            return Optional.empty();
        } catch (ExecutionException executionException) {
            deployTraceRecorder.cancel(compensationTraceId);
            final Throwable cause = executionException.getCause() == null
                    ? executionException
                    : executionException.getCause();
            deployStorePort.markTargetCompensationFailed(
                    deployTargetKey,
                    step.order(),
                    step.code(),
                    "COMPENSATION_EXECUTION_FAILED",
                    cause.getMessage() == null
                            ? "보상 step 응답 처리 중 오류가 발생했습니다."
                            : cause.getMessage(),
                    OffsetDateTime.now(ZoneOffset.UTC)
            );
            return Optional.empty();
        }
    }
}
