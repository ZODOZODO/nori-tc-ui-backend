package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nori.tc.ui.core.deploy.DeployResolvedStepPlan;
import com.nori.tc.ui.core.deploy.DeployRollbackMode;
import com.nori.tc.ui.core.eqp.EqpRepairAction;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import com.nori.tc.ui.core.port.db.ModelDetailQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * direct EQP scenario의 remote/local rollback을 담당합니다.
 */
@Service
public class EqpDirectScenarioRollbackService {

    private static final Logger log = LoggerFactory.getLogger(EqpDirectScenarioRollbackService.class);

    private final DeployCommandAssembler commandAssembler;
    private final DeployTraceRecorder deployTraceRecorder;
    private final EqpRepairTaskService repairTaskService;
    private final EqpDirectScenarioLocalMutationCoordinator localMutationCoordinator;
    private final DeployServiceSnapshotSupport snapshotSupport;

    public EqpDirectScenarioRollbackService(
            final DeployCommandAssembler commandAssembler,
            final DeployTraceRecorder deployTraceRecorder,
            final EqpRepairTaskService repairTaskService,
            final EqpDirectScenarioLocalMutationCoordinator localMutationCoordinator,
            final EqpCrudPort eqpCrudPort,
            final ModelDetailQueryPort modelDetailQueryPort,
            final ObjectMapper objectMapper
    ) {
        this.commandAssembler = Objects.requireNonNull(commandAssembler, "commandAssembler is null");
        this.deployTraceRecorder = Objects.requireNonNull(deployTraceRecorder, "deployTraceRecorder is null");
        this.repairTaskService = Objects.requireNonNull(repairTaskService, "repairTaskService is null");
        this.localMutationCoordinator = Objects.requireNonNull(localMutationCoordinator, "localMutationCoordinator is null");
        this.snapshotSupport = new DeployServiceSnapshotSupport(
                Objects.requireNonNull(eqpCrudPort, "eqpCrudPort is null"),
                Objects.requireNonNull(modelDetailQueryPort, "modelDetailQueryPort is null"),
                Objects.requireNonNull(objectMapper, "objectMapper is null")
        );
    }

    public void rollback(
            final EqpDirectScenarioContext context,
            final List<DeployResolvedStepPlan> completedSteps,
            final String originalErrorCode,
            final String originalErrorMessage
    ) {
        final boolean remoteRollbackSucceeded = rollbackCompletedSteps(
                context,
                completedSteps,
                originalErrorCode,
                originalErrorMessage
        );
        if (!remoteRollbackSucceeded) {
            return;
        }

        try {
            localMutationCoordinator.compensateLocalFailure(context);
        } catch (Exception exception) {
            log.error("direct local rollback 실패. eventType={}, eqpId={}", context.eventType(), context.eqpId(), exception);
            recordRepairTask(
                    context,
                    originalErrorCode,
                    exception.getMessage() == null ? "local rollback 중 오류가 발생했습니다." : exception.getMessage()
            );
        }
    }

    private boolean rollbackCompletedSteps(
            final EqpDirectScenarioContext context,
            final List<DeployResolvedStepPlan> completedSteps,
            final String originalErrorCode,
            final String originalErrorMessage
    ) {
        for (int index = completedSteps.size() - 1; index >= 0; index--) {
            final DeployResolvedStepPlan step = completedSteps.get(index);
            if (!step.rollbackEnabled()) {
                continue;
            }
            if (step.rollbackMode() == DeployRollbackMode.MANUAL_ONLY || step.rollbackAction() == null) {
                recordRepairTask(
                        context,
                        "MANUAL_ROLLBACK_REQUIRED",
                        "수동 rollback 이 필요한 step 입니다. stepCode=" + step.code()
                );
                return false;
            }

            final JsonNode compensationSnapshot = snapshotSupport.buildCompensationSnapshot(
                    context.syntheticTarget(),
                    step,
                    context.targetPreState()
            );
            if (compensationSnapshot.path("skip").asBoolean(false)) {
                continue;
            }

            final String compensationTraceId = buildRollbackTraceId(context.executionId(), step.code());
            final long timeoutMs = compensationSnapshot.path("timeoutMs").asLong(step.timeoutMs());
            final Set<String> expectedSources = commandAssembler.expectedSources(step.targetApp());
            final var replyFuture = deployTraceRecorder.register(compensationTraceId, expectedSources, timeoutMs);

            try {
                commandAssembler.publishDirectCommand(
                        step.targetApp(),
                        commandAssembler.parseCompensationEventType(
                                DeployServiceCommandSupport.textValue(compensationSnapshot, "eventType")
                        ),
                        context.eqpId(),
                        Optional.ofNullable(DeployServiceCommandSupport.textValue(compensationSnapshot, "interfaceType"))
                                .orElse(context.interfaceType()),
                        compensationSnapshot.path("uiMessage"),
                        commandAssembler.parseEquipmentProfile(compensationSnapshot.get("equipmentProfile")),
                        compensationTraceId
                );
            } catch (Exception publishException) {
                deployTraceRecorder.cancel(compensationTraceId);
                log.error("direct rollback publish 실패. eventType={}, eqpId={}, stepCode={}",
                        context.eventType(), context.eqpId(), step.code(), publishException);
                recordRepairTask(
                        context,
                        "COMPENSATION_PUBLISH_FAILED",
                        publishException.getMessage() == null
                                ? "보상 Kafka 발행 중 오류가 발생했습니다."
                                : publishException.getMessage()
                );
                return false;
            }

            try {
                final var completion = replyFuture.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (!completion.success()) {
                    recordRepairTask(
                            context,
                            commandAssembler.normalizeOptionalText(completion.firstErrorCode()),
                            commandAssembler.normalizeOptionalText(completion.firstErrorMessage())
                    );
                    return false;
                }
            } catch (TimeoutException timeoutException) {
                deployTraceRecorder.cancel(compensationTraceId);
                recordRepairTask(context, "COMPENSATION_TIMEOUT", "보상 step 응답 대기 시간이 초과되었습니다.");
                return false;
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                deployTraceRecorder.cancel(compensationTraceId);
                recordRepairTask(context, "COMPENSATION_INTERRUPTED", "보상 step 실행 대기 중 인터럽트가 발생했습니다.");
                return false;
            } catch (CancellationException cancellationException) {
                recordRepairTask(context, "COMPENSATION_CANCELED", "보상 step 대기가 취소되었습니다.");
                return false;
            } catch (ExecutionException executionException) {
                deployTraceRecorder.cancel(compensationTraceId);
                final Throwable cause = executionException.getCause() == null ? executionException : executionException.getCause();
                if (cause instanceof CancellationException) {
                    recordRepairTask(context, "COMPENSATION_CANCELED", "보상 step 대기가 취소되었습니다.");
                    return false;
                }
                recordRepairTask(
                        context,
                        "COMPENSATION_EXECUTION_FAILED",
                        cause.getMessage() == null
                                ? "보상 step 응답 처리 중 오류가 발생했습니다."
                                : cause.getMessage()
                );
                return false;
            }
        }
        return true;
    }

    private void recordRepairTask(
            final EqpDirectScenarioContext context,
            final String errorCode,
            final String errorMessage
    ) {
        repairTaskService.record(
                context.eqpId(),
                context.eventType().name(),
                repairAction(context.eventType()),
                errorCode,
                errorMessage
        );
    }

    private EqpRepairAction repairAction(final com.nori.tc.ui.core.model.UiCommandEventType eventType) {
        return switch (eventType) {
            case EQP_CREATE -> EqpRepairAction.RUNTIME_DELETE;
            case EQP_DELETE -> EqpRepairAction.RUNTIME_CREATE;
            case EQP_GATEWAY_JARFILE_UPDATE, EQP_BUSINESS_JARFILE_UPDATE -> EqpRepairAction.JAR_RELOAD;
            case EQP_UPDATE,
                 EQP_MODEL_UPDATE,
                 EQP_START,
                 EQP_END,
                 EQP_SEND_MESSAGE,
                 EQP_RECONNECT -> EqpRepairAction.RUNTIME_UPDATE;
            case EQP_REGISTER_DEPLOY -> EqpRepairAction.RUNTIME_UPDATE;
            default -> throw new IllegalArgumentException("지원하지 않는 direct eventType 입니다. eventType=" + eventType);
        };
    }

    private String buildRollbackTraceId(final String executionId, final String stepCode) {
        return DeployTraceRegistry.DIRECT_TRACE_PREFIX
                + executionId
                + "-RB-"
                + stepCode
                + "-ATT-1";
    }
}
