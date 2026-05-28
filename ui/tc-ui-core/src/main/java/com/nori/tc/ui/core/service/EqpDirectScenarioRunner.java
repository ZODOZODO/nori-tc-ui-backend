package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import com.nori.tc.common.runtime.support.TraceIdGenerator;
import com.nori.tc.ui.core.deploy.DeployResolvedStepPlan;
import com.nori.tc.ui.core.deploy.DeployScenarioAction;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.model.UiEquipmentProfileSnapshot;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * direct EQP scenario step 실행과 reply wait를 담당합니다.
 */
@Service
public class EqpDirectScenarioRunner {

    private static final int CONFLICT_STATUS = 409;
    private static final int INTERNAL_ERROR_STATUS = 500;
    private static final int TIMEOUT_STATUS = 504;

    private final DeployCommandAssembler commandAssembler;
    private final DeployTraceRecorder deployTraceRecorder;
    private final EqpDirectScenarioLocalMutationCoordinator localMutationCoordinator;
    private final EqpDirectScenarioRollbackService rollbackService;

    public EqpDirectScenarioRunner(
            final DeployCommandAssembler commandAssembler,
            final DeployTraceRecorder deployTraceRecorder,
            final EqpDirectScenarioLocalMutationCoordinator localMutationCoordinator,
            final EqpDirectScenarioRollbackService rollbackService
    ) {
        this.commandAssembler = Objects.requireNonNull(commandAssembler, "commandAssembler is null");
        this.deployTraceRecorder = Objects.requireNonNull(deployTraceRecorder, "deployTraceRecorder is null");
        this.localMutationCoordinator = Objects.requireNonNull(localMutationCoordinator, "localMutationCoordinator is null");
        this.rollbackService = Objects.requireNonNull(rollbackService, "rollbackService is null");
    }

    public void run(final EqpDirectScenarioContext context) throws EqpManagementCommandFailureException {
        final List<DeployResolvedStepPlan> completedSteps = new ArrayList<>();

        try {
            for (DeployResolvedStepPlan step : context.planDraft().resolvedSteps()) {
                final String traceId = buildTraceId(context.executionId(), step.code());
                final Set<String> expectedSources = commandAssembler.expectedSources(step);
                final var replyFuture = deployTraceRecorder.register(traceId, expectedSources, step.timeoutMs());

                try {
                    publishStep(context, step, traceId);
                } catch (Exception publishException) {
                    deployTraceRecorder.cancel(traceId);
                    handleFailure(
                            context,
                            completedSteps,
                            new EqpManagementCommandFailureException(
                                    INTERNAL_ERROR_STATUS,
                                    "PUBLISH_FAILED",
                                    publishException.getMessage() == null
                                            ? "Kafka 발행 중 오류가 발생했습니다."
                                            : publishException.getMessage(),
                                    publishException
                            )
                    );
                }

                try {
                    final var completion = replyFuture.get(step.timeoutMs(), TimeUnit.MILLISECONDS);
                    if (!completion.success()) {
                        handleFailure(
                                context,
                                completedSteps,
                                new EqpManagementCommandFailureException(
                                        CONFLICT_STATUS,
                                        commandAssembler.normalizeOptionalText(completion.firstErrorCode()),
                                        commandAssembler.normalizeOptionalText(completion.firstErrorMessage()) == null
                                                ? "설비 step 실행에 실패했습니다."
                                                : commandAssembler.normalizeOptionalText(completion.firstErrorMessage()),
                                        null
                                )
                        );
                    }
                } catch (TimeoutException timeoutException) {
                    deployTraceRecorder.cancel(traceId);
                    handleFailure(
                            context,
                            completedSteps,
                            new EqpManagementCommandFailureException(
                                    TIMEOUT_STATUS,
                                    "TIMEOUT",
                                    "step 응답 대기 시간이 초과되었습니다.",
                                    timeoutException
                            )
                    );
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    deployTraceRecorder.cancel(traceId);
                    handleFailure(
                            context,
                            completedSteps,
                            new EqpManagementCommandFailureException(
                                    INTERNAL_ERROR_STATUS,
                                    "STEP_INTERRUPTED",
                                    "step 실행 대기 중 인터럽트가 발생했습니다.",
                                    interruptedException
                            )
                    );
                } catch (CancellationException cancellationException) {
                    handleFailure(
                            context,
                            completedSteps,
                            new EqpManagementCommandFailureException(
                                    INTERNAL_ERROR_STATUS,
                                    "STEP_CANCELED",
                                    "step 실행이 취소되었습니다.",
                                    cancellationException
                            )
                    );
                } catch (ExecutionException executionException) {
                    deployTraceRecorder.cancel(traceId);
                    final Throwable cause = executionException.getCause() == null
                            ? executionException
                            : executionException.getCause();
                    handleFailure(
                            context,
                            completedSteps,
                            new EqpManagementCommandFailureException(
                                    INTERNAL_ERROR_STATUS,
                                    "STEP_EXECUTION_FAILED",
                                    cause.getMessage() == null
                                            ? "step 응답 처리 중 오류가 발생했습니다."
                                            : cause.getMessage(),
                                    cause
                            )
                    );
                }

                completedSteps.add(step);
            }

            try {
                localMutationCoordinator.finalizeSuccess(context);
            } catch (Exception finalizationException) {
                handleFailure(
                        context,
                        completedSteps,
                        new EqpManagementCommandFailureException(
                                INTERNAL_ERROR_STATUS,
                                "LOCAL_FINALIZATION_FAILED",
                                finalizationException.getMessage() == null
                                        ? "local finalization 중 오류가 발생했습니다."
                                        : finalizationException.getMessage(),
                                finalizationException
                        )
                );
            }
        } catch (EqpManagementCommandFailureException commandFailureException) {
            throw commandFailureException;
        } catch (Exception unexpectedException) {
            handleFailure(
                    context,
                    completedSteps,
                    new EqpManagementCommandFailureException(
                            INTERNAL_ERROR_STATUS,
                            "INTERNAL_ERROR",
                            unexpectedException.getMessage() == null
                                    ? "direct scenario 실행 중 내부 오류가 발생했습니다."
                                    : unexpectedException.getMessage(),
                            unexpectedException
                    )
            );
        }
    }

    private void publishStep(
            final EqpDirectScenarioContext context,
            final DeployResolvedStepPlan step,
            final String traceId
    ) {
        final UiCommandEventType messageEventType = commandAssembler.resolveMessageEventType(context.eventType(), step.action());
        final JsonNode stepUiMessage = commandAssembler.buildDirectStepUiMessage(
                context.executionId(),
                context.eventType(),
                context.eqpId(),
                step,
                traceId,
                context.actor(),
                context.uiMessage()
        );
        final UiEquipmentProfileSnapshot equipmentProfile = context.executionSnapshot() == null
                ? commandAssembler.buildDirectEquipmentProfile(
                messageEventType,
                context.eqpId(),
                context.interfaceType(),
                null,
                stepUiMessage
        )
                : commandAssembler.buildDirectEquipmentProfile(
                messageEventType,
                context.executionSnapshot(),
                stepUiMessage
        );

        final Integer explicitGatewayPartition = resolveExplicitGatewayPartition(context, step.action());
        commandAssembler.publishDirectCommand(
                step.targetApp(),
                messageEventType,
                context.eqpId(),
                context.interfaceType(),
                stepUiMessage,
                equipmentProfile,
                traceId,
                explicitGatewayPartition
        );
    }

    /**
     * EQP_UPDATE_PARTITION_MOVE 시나리오의 두 step (OLD/NEW) 은 publish target partition 을 명시 전달합니다.
     * 그 외에는 null 을 반환해 publisher 가 DB lookup 으로 결정하도록 합니다.
     */
    private Integer resolveExplicitGatewayPartition(
            final EqpDirectScenarioContext context,
            final DeployScenarioAction action
    ) {
        return switch (action) {
            case UPSERT_GATEWAY_CONTEXT_OLD_PARTITION -> context.previousSnapshot() == null
                    ? null
                    : context.previousSnapshot().eqp().routePartition();
            case UPSERT_GATEWAY_CONTEXT_NEW_PARTITION -> context.executionSnapshot() == null
                    ? null
                    : context.executionSnapshot().eqp().routePartition();
            default -> null;
        };
    }

    private void handleFailure(
            final EqpDirectScenarioContext context,
            final List<DeployResolvedStepPlan> completedSteps,
            final EqpManagementCommandFailureException failure
    ) throws EqpManagementCommandFailureException {
        rollbackService.rollback(context, List.copyOf(completedSteps), failure.errorCode(), failure.getMessage());
        throw failure;
    }

    private String buildTraceId(final String executionId, final String stepCode) {
        return TraceIdGenerator.newTraceId();
    }
}
