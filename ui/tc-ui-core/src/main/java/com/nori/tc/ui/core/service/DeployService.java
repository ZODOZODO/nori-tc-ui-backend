package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.nori.tc.db.domain.eqp.TcEqpParam;
import com.nori.tc.messaging.kafka.contract.GatewayEquipmentProfileSnapshot;
import com.nori.tc.ui.core.deploy.DeployCreateResult;
import com.nori.tc.ui.core.deploy.DeployPlanCommand;
import com.nori.tc.ui.core.deploy.DeployPlanningResult;
import com.nori.tc.ui.core.deploy.DeployRollbackMode;
import com.nori.tc.ui.core.deploy.DeployResolvedStepPlan;
import com.nori.tc.ui.core.deploy.DeployResultMessage;
import com.nori.tc.ui.core.deploy.DeployResultSeverity;
import com.nori.tc.ui.core.deploy.DeployScenarioAction;
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
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.model.UiCommandMessage;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.port.db.DeployStorePort;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import com.nori.tc.ui.core.port.messaging.UiBusinessEventPublishPort;
import com.nori.tc.ui.core.port.messaging.UiGatewayEventPublishPort;
import com.nori.tc.ui.core.properties.UiDeployProperties;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * deploy 생성 / 실행 / polling 조회를 담당합니다.
 */
@Service
public class DeployService {

    private static final String UI_SOURCE = "TC-UI-APP";
    private static final String SOURCE_GATEWAY = "TC-GATEWAY-APP";
    private static final String SOURCE_BUSINESS = "TC-BUSINESS-APP";
    private static final String SNAPSHOT_SOURCE_DB = "DB";
    private static final String SNAPSHOT_SOURCE_ORCHESTRATOR = "ORCHESTRATOR";
    private static final int MAX_TARGET_PAGE_SIZE = 200;

    private final DeployPlanningService deployPlanningService;
    private final DeployStorePort deployStorePort;
    private final EqpCrudPort eqpCrudPort;
    private final UiGatewayEventPublishPort gatewayEventPublishPort;
    private final UiBusinessEventPublishPort businessEventPublishPort;
    private final DeployTraceRegistry deployTraceRegistry;
    private final ObjectMapper objectMapper;
    private final ExecutorService targetExecutor;
    private final ConcurrentHashMap<String, ReentrantLock> eqpExecutionLocks;

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
        this.eqpCrudPort = Objects.requireNonNull(eqpCrudPort, "eqpCrudPort is null");
        this.gatewayEventPublishPort = Objects.requireNonNull(gatewayEventPublishPort, "gatewayEventPublishPort is null");
        this.businessEventPublishPort = Objects.requireNonNull(businessEventPublishPort, "businessEventPublishPort is null");
        this.deployTraceRegistry = Objects.requireNonNull(deployTraceRegistry, "deployTraceRegistry is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
        this.targetExecutor = Executors.newFixedThreadPool(Math.max(1, validatedDeployProperties.targetConcurrency()));
        this.eqpExecutionLocks = new ConcurrentHashMap<>();
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
                normalizeActor(actor)
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
                    completedMessage(new DeployTargetCounts(
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
                normalizeActor(actor)
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

        final boolean terminal = isTerminal(snapshot.deployStatus());
        final String resultCode = resolveResultCode(snapshot.deployStatus(), snapshot.targetSummary());
        final DeployResultMessage resultMessage = resolveSummaryMessage(snapshot.deployStatus(), snapshot.targetSummary());

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
                terminal,
                terminal,
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

        final DeployTargetStatus targetStatus = parseTargetStatus(statusFilter);
        return deployStorePort.findDeployTargets(
                deployKey,
                page,
                size,
                targetStatus,
                normalizeOptionalText(eqpIdFilter)
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
        targetExecutor.shutdownNow();
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
                refreshDeployStatus(deployKey, null, null);
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
        final JsonNode targetPreState = captureTargetPreState(target);

        try {
            for (DeployResolvedStepPlan step : target.planDraft().resolvedSteps()) {
                currentStep = step;
                if (step.rollbackEnabled()) {
                    saveCompensationSnapshot(target, step, targetPreState);
                }

                final String currentTraceId = buildTraceId(
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

                final Set<String> expectedSources = expectedSources(step);
                final var replyFuture = deployTraceRegistry.register(currentTraceId, expectedSources);

                try {
                    publishStepCommand(
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
                    normalizeOptionalText(errorCode),
                    normalizeOptionalText(errorMessage),
                    false,
                    OffsetDateTime.now(ZoneOffset.UTC)
            );
            refreshDeployStatus(deployKey, errorCode, errorMessage);
            return;
        }

        rollbackCompletedSteps(
                deployKey,
                target,
                compensatableSteps,
                normalizeOptionalText(errorCode),
                normalizeOptionalText(errorMessage),
                actor
        );
        refreshDeployStatus(deployKey, errorCode, errorMessage);
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
                compensationEventType = parseCompensationEventType(snapshotData.path("eventType").asString(null));
                compensationInterfaceType = textValue(snapshotData, "interfaceType");
                compensationUiMessage = snapshotData.path("uiMessage");
                equipmentProfile = parseEquipmentProfile(snapshotData.get("equipmentProfile"));
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
            final String compensationTraceId = buildTraceId(
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

            final Set<String> expectedSources = expectedSources(step.targetApp());
            final var replyFuture = deployTraceRegistry.register(compensationTraceId, expectedSources);

            try {
                publishCommand(
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
                        normalizeOptionalText(completion.firstErrorCode()),
                        normalizeOptionalText(completion.firstErrorMessage()),
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

    private void refreshDeployStatus(
            final long deployKey,
            final String lastErrorCode,
            final String lastErrorMessage
    ) {
        final DeployTargetCounts counts = deployStorePort.summarizeTargets(deployKey);
        if (counts.hasActiveTarget()) {
            return;
        }

        final DeployStatus deployStatus;
        if (counts.hasFailure()) {
            deployStatus = counts.successLikeCount() > 0
                    ? DeployStatus.PARTIAL_FAILED
                    : DeployStatus.FAILED;
        } else {
            deployStatus = DeployStatus.COMPLETED;
        }

        deployStorePort.updateDeployStatus(
                deployKey,
                deployStatus,
                null,
                OffsetDateTime.now(ZoneOffset.UTC),
                normalizeOptionalText(lastErrorCode),
                normalizeOptionalText(lastErrorMessage)
        );
    }

    private void publishStepCommand(
            final long deployKey,
            final UiCommandEventType deployEventType,
            final PersistedDeployTarget target,
            final DeployResolvedStepPlan step,
            final String traceId,
            final String actor
    ) {
        final UiCommandEventType messageEventType = resolveMessageEventType(deployEventType, step.action());
        final JsonNode stepUiMessage = buildStepUiMessage(
                deployKey,
                target.deployTargetKey(),
                step,
                traceId,
                actor,
                target.planDraft().desiredState().mergedUiMessage()
        );
        final GatewayEquipmentProfileSnapshot equipmentProfile =
                buildEquipmentProfile(messageEventType, target, stepUiMessage);

        publishCommand(
                deployKey,
                step.targetApp(),
                messageEventType,
                target,
                target.planDraft().interfaceType(),
                stepUiMessage,
                equipmentProfile,
                traceId,
                actor
        );
    }

    private void publishCommand(
            final long deployKey,
            final com.nori.tc.ui.core.deploy.DeployTargetApp targetApp,
            final UiCommandEventType eventType,
            final PersistedDeployTarget target,
            final String interfaceType,
            final JsonNode uiMessage,
            final GatewayEquipmentProfileSnapshot equipmentProfile,
            final String traceId,
            final String actor
    ) {
        final JsonNode normalizedUiMessage = ensureDeployMetadata(
                deployKey,
                uiMessage,
                traceId,
                target,
                targetApp,
                eventType,
                actor
        );
        final UiCommandMessage message = new UiCommandMessage(
                eventType,
                traceId,
                UI_SOURCE,
                target.planDraft().eqpId(),
                interfaceType == null ? target.planDraft().interfaceType() : interfaceType,
                normalizedUiMessage,
                equipmentProfile
        );

        if (targetApp == com.nori.tc.ui.core.deploy.DeployTargetApp.GATEWAY) {
            gatewayEventPublishPort.publish(message);
            return;
        }
        businessEventPublishPort.publish(message);
    }

    private Set<String> expectedSources(final DeployResolvedStepPlan step) {
        return expectedSources(step.targetApp());
    }

    private Set<String> expectedSources(final com.nori.tc.ui.core.deploy.DeployTargetApp targetApp) {
        return switch (targetApp) {
            case GATEWAY -> Set.of(SOURCE_GATEWAY);
            case BUSINESS -> Set.of(SOURCE_BUSINESS);
        };
    }

    private UiCommandEventType resolveMessageEventType(
            final UiCommandEventType deployEventType,
            final DeployScenarioAction action
    ) {
        return switch (action) {
            case START_GATEWAY_RUNTIME -> UiCommandEventType.EQP_START;
            case END_GATEWAY_RUNTIME, END_EQP_IF_RUNNING -> UiCommandEventType.EQP_END;
            case RECONNECT_GATEWAY -> UiCommandEventType.EQP_RECONNECT;
            case SEND_MESSAGE -> UiCommandEventType.EQP_SEND_MESSAGE;
            case UPDATE_EQP_PARAM -> UiCommandEventType.EQP_PARAMETER_UPDATE;
            case RELOAD_GATEWAY_PLUGIN -> UiCommandEventType.EQP_GATEWAY_JARFILE_UPDATE;
            case RELOAD_BUSINESS_PLUGIN -> UiCommandEventType.EQP_BUSINESS_JARFILE_UPDATE;
            case DELETE_GATEWAY_MEMORY, DELETE_BUSINESS_BINDING -> UiCommandEventType.EQP_DELETE;
            case UPSERT_GATEWAY_CONTEXT,
                 REBUILD_GATEWAY_RUNTIME,
                 RELOAD_BUSINESS_MODEL_RUNTIME,
                 UPDATE_MODEL_BINDING -> switch (deployEventType) {
                case EQP_CREATE -> UiCommandEventType.EQP_CREATE;
                case EQP_UPDATE, EQP_GATEWAY_JARFILE_UPDATE, EQP_BUSINESS_JARFILE_UPDATE -> UiCommandEventType.EQP_UPDATE;
                default -> UiCommandEventType.EQP_UPDATE;
            };
            case RESTORE_PREVIOUS_MODEL_BINDING,
                 RESTORE_PREVIOUS_BUSINESS_RUNTIME,
                 RESTORE_PREVIOUS_BUSINESS_PLUGIN,
                 RESTORE_PREVIOUS_EQP_PARAM,
                 RESTORE_PREVIOUS_GATEWAY_CONTEXT,
                 RESTORE_PREVIOUS_GATEWAY_RUNTIME,
                 RESTORE_PREVIOUS_GATEWAY_PLUGIN,
                 RESTORE_PREVIOUS_BUSINESS_BINDING ->
                    throw new UiBadRequestException("rollback action은 T4에서 직접 실행하지 않습니다. action=" + action);
        };
    }

    private JsonNode buildStepUiMessage(
            final long deployKey,
            final long deployTargetKey,
            final DeployResolvedStepPlan step,
            final String traceId,
            final String actor,
            final JsonNode mergedUiMessage
    ) {
        final ObjectNode root = objectMapper.createObjectNode();
        copyCommonDeployPayload(root, mergedUiMessage, step.action());

        final ObjectNode orchestration = root.putObject("_deploy");
        orchestration.put("deployKey", deployKey);
        orchestration.put("deployTargetKey", deployTargetKey);
        orchestration.put("stepCode", step.code());
        orchestration.put("stepAction", step.action().name());
        orchestration.put("traceId", traceId);
        orchestration.put("requestedBy", actor);

        return root;
    }

    private void copyCommonDeployPayload(
            final ObjectNode destination,
            final JsonNode mergedUiMessage,
            final DeployScenarioAction action
    ) {
        switch (action) {
            case UPSERT_GATEWAY_CONTEXT -> copyFields(destination, mergedUiMessage, "gatewayProfile");
            case RELOAD_BUSINESS_PLUGIN -> copyFields(destination, mergedUiMessage, "modelInfo", "businessJar");
            case RELOAD_BUSINESS_MODEL_RUNTIME -> copyFields(destination, mergedUiMessage, "modelInfo");
            case UPDATE_MODEL_BINDING -> copyFields(destination, mergedUiMessage, "modelInfo", "businessJar");
            case UPDATE_EQP_PARAM -> copyFields(destination, mergedUiMessage, "eqpParams");
            case RELOAD_GATEWAY_PLUGIN -> copyFields(destination, mergedUiMessage, "gatewayJar");
            case REBUILD_GATEWAY_RUNTIME -> copyFields(destination, mergedUiMessage, "gatewayProfile", "gatewayJar");
            case RECONNECT_GATEWAY -> copyFields(destination, mergedUiMessage, "gatewayProfile");
            case START_GATEWAY_RUNTIME, END_GATEWAY_RUNTIME, END_EQP_IF_RUNNING ->
                    destination.put("reason", "DEPLOY_REQUEST");
            case DELETE_GATEWAY_MEMORY, DELETE_BUSINESS_BINDING -> {
                destination.put("deleteMode", "SAFE");
                copyFields(destination, mergedUiMessage, "modelInfo", "businessJar");
            }
            case SEND_MESSAGE -> copyAllFields(destination, mergedUiMessage);
            case RESTORE_PREVIOUS_MODEL_BINDING,
                 RESTORE_PREVIOUS_BUSINESS_RUNTIME,
                 RESTORE_PREVIOUS_BUSINESS_PLUGIN,
                 RESTORE_PREVIOUS_EQP_PARAM,
                 RESTORE_PREVIOUS_GATEWAY_CONTEXT,
                 RESTORE_PREVIOUS_GATEWAY_RUNTIME,
                 RESTORE_PREVIOUS_GATEWAY_PLUGIN,
                 RESTORE_PREVIOUS_BUSINESS_BINDING ->
                    throw new UiBadRequestException("rollback payload는 T4에서 생성하지 않습니다. action=" + action);
        }
    }

    private GatewayEquipmentProfileSnapshot buildEquipmentProfile(
            final UiCommandEventType messageEventType,
            final PersistedDeployTarget target,
            final JsonNode stepUiMessage
    ) {
        if (messageEventType != UiCommandEventType.EQP_CREATE && messageEventType != UiCommandEventType.EQP_UPDATE) {
            return null;
        }
        final JsonNode gatewayProfile = stepUiMessage.get("gatewayProfile");
        if (gatewayProfile == null || !gatewayProfile.isObject()) {
            return null;
        }

        final Long eqpKey = target.planDraft().currentState() == null ? null : target.planDraft().currentState().eqpKey();
        return new GatewayEquipmentProfileSnapshot(
                eqpKey,
                target.planDraft().eqpId(),
                target.planDraft().interfaceType(),
                textValue(gatewayProfile, "socketType"),
                intValue(gatewayProfile, "hsmsDeviceId"),
                textValue(gatewayProfile, "eqpIp"),
                intValue(gatewayProfile, "eqpPort"),
                null,
                textValue(gatewayProfile, "connectionMode"),
                null,
                true,
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                OffsetDateTime.now(ZoneOffset.UTC).toString()
        );
    }

    private JsonNode captureTargetPreState(final PersistedDeployTarget target) {
        final boolean hasRollbackStep = target.planDraft().resolvedSteps().stream()
                .anyMatch(DeployResolvedStepPlan::rollbackEnabled);
        if (!hasRollbackStep) {
            return null;
        }

        final JsonNode snapshot = buildTargetPreStateSnapshot(target);
        deployStorePort.saveTargetSnapshot(
                target.deployTargetKey(),
                DeploySnapshotScope.TARGET_PRE_STATE,
                null,
                DeploySnapshotKind.TARGET_FULL_STATE,
                SNAPSHOT_SOURCE_DB,
                snapshot
        );
        return snapshot;
    }

    private void saveCompensationSnapshot(
            final PersistedDeployTarget target,
            final DeployResolvedStepPlan step,
            final JsonNode targetPreState
    ) {
        final JsonNode compensationSnapshot = buildCompensationSnapshot(target, step, targetPreState);
        deployStorePort.saveTargetSnapshot(
                target.deployTargetKey(),
                DeploySnapshotScope.STEP_PRE_STATE,
                step.code(),
                DeploySnapshotKind.COMPENSATION_COMMAND,
                SNAPSHOT_SOURCE_ORCHESTRATOR,
                compensationSnapshot
        );
    }

    private JsonNode buildTargetPreStateSnapshot(final PersistedDeployTarget target) {
        if (target.planDraft().currentState() == null) {
            return buildTargetPreStateMarker(target.planDraft().eqpId(), target.planDraft().interfaceType(), false);
        }

        final Optional<EqpManagementSnapshot> snapshot = eqpCrudPort.findSnapshotByEqpId(target.planDraft().eqpId());
        if (snapshot.isPresent()) {
            return sanitizeTargetPreState(snapshot.get());
        }

        return buildFallbackTargetPreState(target);
    }

    private JsonNode sanitizeTargetPreState(final EqpManagementSnapshot snapshot) {
        final ObjectNode root = objectMapper.createObjectNode();
        root.put("eqpExists", true);
        root.put("capturedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        root.put("eqpId", snapshot.eqp().eqpId());
        root.put("interfaceType", snapshot.eqp().commInterface() == null ? null : snapshot.eqp().commInterface().name());

        final ObjectNode modelInfo = root.putObject("modelInfo");
        modelInfo.put("modelVersionKey", snapshot.eqp().modelVersionKey());
        if (snapshot.model() != null) {
            modelInfo.put("modelKey", snapshot.model().modelKey());
            if (snapshot.model().modelVersion() != null) {
                modelInfo.put("modelVersion", snapshot.model().modelVersion());
            }
            if (snapshot.model().modelName() != null) {
                modelInfo.put("modelName", snapshot.model().modelName());
            }
        }

        root.set("gatewayProfile", buildGatewayProfile(snapshot));
        root.set("equipmentProfile", objectMapper.valueToTree(buildGatewayEquipmentProfile(snapshot)));
        root.set("eqpParams", buildEqpParamSnapshot(snapshot.eqp().appliedParamVersion(), snapshot.params()));

        if (snapshot.gatewayJar() != null) {
            root.set("gatewayJar", buildJarLocator(snapshot.gatewayJar().jarFileName(), snapshot.gatewayJar().jarFile()));
        }
        if (snapshot.businessJar() != null) {
            root.set("businessJar", buildJarLocator(snapshot.businessJar().jarFileName(), snapshot.businessJar().jarFile()));
        }
        return root;
    }

    private JsonNode buildFallbackTargetPreState(final PersistedDeployTarget target) {
        final ObjectNode root = buildTargetPreStateMarker(
                target.planDraft().eqpId(),
                target.planDraft().interfaceType(),
                true
        );
        final var currentState = target.planDraft().currentState();
        if (currentState == null) {
            return root;
        }
        final String capturedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        root.put("capturedAt", capturedAt);

        final ObjectNode modelInfo = root.putObject("modelInfo");
        modelInfo.put("modelVersionKey", currentState.modelVersionKey());

        final ObjectNode gatewayProfile = root.putObject("gatewayProfile");
        gatewayProfile.put("connectionMode", currentState.connectionMode());
        gatewayProfile.put("eqpIp", currentState.eqpIp());
        gatewayProfile.put("eqpPort", currentState.eqpPort());
        if (currentState.socketType() != null) {
            gatewayProfile.put("socketType", currentState.socketType());
        }
        if (currentState.hsmsDeviceId() != null) {
            gatewayProfile.put("hsmsDeviceId", currentState.hsmsDeviceId());
        }

        final ObjectNode eqpParams = root.putObject("eqpParams");
        if (currentState.appliedParamVersion() != null) {
            eqpParams.put("version", currentState.appliedParamVersion());
        }
        eqpParams.putArray("items");
        root.set("equipmentProfile", objectMapper.valueToTree(new GatewayEquipmentProfileSnapshot(
                currentState.eqpKey(),
                currentState.eqpId(),
                currentState.interfaceType(),
                currentState.socketType(),
                currentState.hsmsDeviceId(),
                currentState.eqpIp(),
                currentState.eqpPort(),
                null,
                currentState.connectionMode(),
                null,
                true,
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                capturedAt
        )));
        return root;
    }

    private ObjectNode buildTargetPreStateMarker(
            final String eqpId,
            final String interfaceType,
            final boolean eqpExists
    ) {
        final ObjectNode root = objectMapper.createObjectNode();
        root.put("eqpExists", eqpExists);
        root.put("capturedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        root.put("eqpId", eqpId);
        root.put("interfaceType", interfaceType);
        return root;
    }

    private JsonNode buildCompensationSnapshot(
            final PersistedDeployTarget target,
            final DeployResolvedStepPlan step,
            final JsonNode targetPreState
    ) {
        if (step.rollbackMode() == DeployRollbackMode.MANUAL_ONLY || step.rollbackAction() == null) {
            return buildSkipCompensationSnapshot("MANUAL_ONLY");
        }

        final boolean eqpExists = targetPreState != null && targetPreState.path("eqpExists").asBoolean(false);

        return switch (step.rollbackAction()) {
            case RESTORE_PREVIOUS_GATEWAY_CONTEXT -> eqpExists
                    ? buildGatewayUpdateCompensationSnapshot(targetPreState, step.timeoutMs(), false)
                    : buildDeleteCompensationSnapshot(UiCommandEventType.EQP_DELETE, target.planDraft().interfaceType(), step.timeoutMs());
            case RESTORE_PREVIOUS_GATEWAY_RUNTIME -> eqpExists
                    ? buildGatewayUpdateCompensationSnapshot(targetPreState, step.timeoutMs(), true)
                    : buildSkipCompensationSnapshot("NO_PREVIOUS_GATEWAY_RUNTIME");
            case RESTORE_PREVIOUS_GATEWAY_PLUGIN -> eqpExists
                    ? buildGatewayJarCompensationSnapshot(targetPreState, step.timeoutMs())
                    : buildSkipCompensationSnapshot("NO_PREVIOUS_GATEWAY_PLUGIN");
            case RESTORE_PREVIOUS_BUSINESS_PLUGIN -> eqpExists
                    ? buildBusinessJarCompensationSnapshot(targetPreState, step.timeoutMs())
                    : buildSkipCompensationSnapshot("NO_PREVIOUS_BUSINESS_PLUGIN");
            case RESTORE_PREVIOUS_BUSINESS_RUNTIME -> eqpExists
                    ? buildBusinessUpdateCompensationSnapshot(targetPreState, step.timeoutMs(), false, false)
                    : buildSkipCompensationSnapshot("NO_PREVIOUS_BUSINESS_RUNTIME");
            case RESTORE_PREVIOUS_MODEL_BINDING -> eqpExists
                    ? buildBusinessUpdateCompensationSnapshot(targetPreState, step.timeoutMs(), true, false)
                    : buildDeleteCompensationSnapshot(UiCommandEventType.EQP_DELETE, target.planDraft().interfaceType(), step.timeoutMs());
            case RESTORE_PREVIOUS_EQP_PARAM -> eqpExists
                    ? buildEqpParamCompensationSnapshot(targetPreState, step.timeoutMs())
                    : buildSkipCompensationSnapshot("NO_PREVIOUS_EQP_PARAM");
            case RESTORE_PREVIOUS_BUSINESS_BINDING -> eqpExists
                    ? buildBusinessUpdateCompensationSnapshot(targetPreState, step.timeoutMs(), true, true)
                    : buildDeleteCompensationSnapshot(UiCommandEventType.EQP_DELETE, target.planDraft().interfaceType(), step.timeoutMs());
            default -> buildSkipCompensationSnapshot("UNSUPPORTED_ROLLBACK_ACTION");
        };
    }

    private JsonNode buildGatewayUpdateCompensationSnapshot(
            final JsonNode targetPreState,
            final long timeoutMs,
            final boolean includeGatewayJar
    ) {
        final ObjectNode uiMessage = objectMapper.createObjectNode();
        copyFields(uiMessage, targetPreState, "gatewayProfile");
        if (includeGatewayJar) {
            copyFields(uiMessage, targetPreState, "gatewayJar");
        }
        return buildCommandSnapshot(
                UiCommandEventType.EQP_UPDATE,
                textValue(targetPreState, "interfaceType"),
                uiMessage,
                targetPreState.get("equipmentProfile"),
                timeoutMs
        );
    }

    private JsonNode buildBusinessUpdateCompensationSnapshot(
            final JsonNode targetPreState,
            final long timeoutMs,
            final boolean includeBusinessJar,
            final boolean includeEqpParams
    ) {
        final ObjectNode uiMessage = objectMapper.createObjectNode();
        copyFields(uiMessage, targetPreState, "modelInfo");
        if (includeBusinessJar) {
            copyFields(uiMessage, targetPreState, "businessJar");
        }
        if (includeEqpParams) {
            copyFields(uiMessage, targetPreState, "eqpParams");
        }
        return buildCommandSnapshot(
                UiCommandEventType.EQP_UPDATE,
                textValue(targetPreState, "interfaceType"),
                uiMessage,
                null,
                timeoutMs
        );
    }

    private JsonNode buildBusinessJarCompensationSnapshot(
            final JsonNode targetPreState,
            final long timeoutMs
    ) {
        final JsonNode businessJar = targetPreState == null ? null : targetPreState.get("businessJar");
        if (businessJar == null || businessJar.isNull()) {
            return buildSkipCompensationSnapshot("MISSING_PREVIOUS_BUSINESS_JAR");
        }
        final ObjectNode uiMessage = objectMapper.createObjectNode();
        copyFields(uiMessage, targetPreState, "modelInfo", "businessJar");
        return buildCommandSnapshot(
                UiCommandEventType.EQP_BUSINESS_JARFILE_UPDATE,
                textValue(targetPreState, "interfaceType"),
                uiMessage,
                null,
                timeoutMs
        );
    }

    private JsonNode buildGatewayJarCompensationSnapshot(
            final JsonNode targetPreState,
            final long timeoutMs
    ) {
        final JsonNode gatewayJar = targetPreState == null ? null : targetPreState.get("gatewayJar");
        if (gatewayJar == null || gatewayJar.isNull()) {
            return buildSkipCompensationSnapshot("MISSING_PREVIOUS_GATEWAY_JAR");
        }
        final ObjectNode uiMessage = objectMapper.createObjectNode();
        copyFields(uiMessage, targetPreState, "gatewayJar");
        return buildCommandSnapshot(
                UiCommandEventType.EQP_GATEWAY_JARFILE_UPDATE,
                textValue(targetPreState, "interfaceType"),
                uiMessage,
                null,
                timeoutMs
        );
    }

    private JsonNode buildEqpParamCompensationSnapshot(
            final JsonNode targetPreState,
            final long timeoutMs
    ) {
        final ObjectNode uiMessage = objectMapper.createObjectNode();
        copyFields(uiMessage, targetPreState, "eqpParams");
        return buildCommandSnapshot(
                UiCommandEventType.EQP_PARAMETER_UPDATE,
                textValue(targetPreState, "interfaceType"),
                uiMessage,
                null,
                timeoutMs
        );
    }

    private JsonNode buildDeleteCompensationSnapshot(
            final UiCommandEventType eventType,
            final String interfaceType,
            final long timeoutMs
    ) {
        final ObjectNode uiMessage = objectMapper.createObjectNode();
        uiMessage.put("deleteMode", "SAFE");
        return buildCommandSnapshot(eventType, interfaceType, uiMessage, null, timeoutMs);
    }

    private JsonNode buildSkipCompensationSnapshot(final String reason) {
        final ObjectNode root = objectMapper.createObjectNode();
        root.put("skip", true);
        root.put("reason", reason);
        return root;
    }

    private JsonNode buildCommandSnapshot(
            final UiCommandEventType eventType,
            final String interfaceType,
            final JsonNode uiMessage,
            final JsonNode equipmentProfile,
            final long timeoutMs
    ) {
        final ObjectNode root = objectMapper.createObjectNode();
        root.put("skip", false);
        root.put("eventType", eventType.name());
        if (interfaceType != null) {
            root.put("interfaceType", interfaceType);
        }
        root.put("timeoutMs", timeoutMs);
        root.set("uiMessage", uiMessage == null ? objectMapper.createObjectNode() : uiMessage.deepCopy());
        if (equipmentProfile != null && !equipmentProfile.isNull()) {
            root.set("equipmentProfile", equipmentProfile.deepCopy());
        }
        return root;
    }

    private JsonNode ensureDeployMetadata(
            final long deployKey,
            final JsonNode uiMessage,
            final String traceId,
            final PersistedDeployTarget target,
            final com.nori.tc.ui.core.deploy.DeployTargetApp targetApp,
            final UiCommandEventType eventType,
            final String actor
    ) {
        final ObjectNode root = uiMessage != null && uiMessage.isObject()
                ? ((ObjectNode) uiMessage.deepCopy())
                : objectMapper.createObjectNode();
        final JsonNode existing = root.get("_deploy");
        final ObjectNode orchestration = existing instanceof ObjectNode objectNode
                ? objectNode
                : root.putObject("_deploy");
        orchestration.put("deployKey", deployKey);
        orchestration.put("deployTargetKey", target.deployTargetKey());
        orchestration.put("targetApp", targetApp.name());
        orchestration.put("eventType", eventType.name());
        orchestration.put("traceId", traceId);
        orchestration.put("requestedBy", actor);
        return root;
    }

    private ObjectNode buildGatewayProfile(final EqpManagementSnapshot snapshot) {
        final ObjectNode gatewayProfile = objectMapper.createObjectNode();
        gatewayProfile.put("connectionMode", snapshot.eqp().commMode());
        gatewayProfile.put("eqpIp", snapshot.eqp().eqpIp());
        gatewayProfile.put("eqpPort", snapshot.eqp().eqpPort());

        if (snapshot.socket() != null) {
            gatewayProfile.put("socketType", snapshot.socket().socketProtocolType());
            final ObjectNode socketSettings = gatewayProfile.putObject("socketSettings");
            socketSettings.put("socketProtocolType", snapshot.socket().socketProtocolType());
            socketSettings.put("connectionMode", snapshot.eqp().commMode());
            socketSettings.put("charset", snapshot.socket().charset());
            socketSettings.put("heartbeatEnabled", snapshot.socket().heartbeatEnabled());
            socketSettings.put("heartbeatInterval", snapshot.socket().heartbeatInterval());
            socketSettings.put("readTimeout", snapshot.socket().readTimeout());
            socketSettings.put("writeTimeout", snapshot.socket().writeTimeout());
            socketSettings.put("maxFrameSizeBytes", snapshot.socket().maxFrameSizeBytes());
            socketSettings.put("keepAliveEnabled", snapshot.socket().keepAliveEnabled());
        }

        if (snapshot.hsms() != null) {
            gatewayProfile.put("hsmsDeviceId", snapshot.hsms().deviceId());
            final ObjectNode hsmsSettings = gatewayProfile.putObject("hsmsSettings");
            hsmsSettings.put("deviceId", snapshot.hsms().deviceId());
            hsmsSettings.put("connectionMode", snapshot.eqp().commMode());
            hsmsSettings.put("t3Timeout", snapshot.hsms().t3Timeout());
            hsmsSettings.put("t5Timeout", snapshot.hsms().t5Timeout());
            hsmsSettings.put("t6Timeout", snapshot.hsms().t6Timeout());
            hsmsSettings.put("t7Timeout", snapshot.hsms().t7Timeout());
            hsmsSettings.put("t8Timeout", snapshot.hsms().t8Timeout());
            hsmsSettings.put("linkTestEnabled", snapshot.hsms().linkTestEnabled());
            hsmsSettings.put("linkTestInterval", snapshot.hsms().linkTestInterval());
            hsmsSettings.put("maxMsgBytes", snapshot.hsms().maxMsgBytes());
        }
        return gatewayProfile;
    }

    private GatewayEquipmentProfileSnapshot buildGatewayEquipmentProfile(final EqpManagementSnapshot snapshot) {
        return new GatewayEquipmentProfileSnapshot(
                snapshot.eqp().eqpKey(),
                snapshot.eqp().eqpId(),
                snapshot.eqp().commInterface() == null ? null : snapshot.eqp().commInterface().name(),
                snapshot.socket() == null ? null : snapshot.socket().socketProtocolType(),
                snapshot.hsms() == null ? null : snapshot.hsms().deviceId(),
                snapshot.eqp().eqpIp(),
                snapshot.eqp().eqpPort(),
                snapshot.model() == null ? null : snapshot.model().modelKey(),
                snapshot.eqp().commMode(),
                snapshot.eqp().routePartition(),
                snapshot.eqp().enabled(),
                snapshot.hsms() == null ? null : new GatewayEquipmentProfileSnapshot.HsmsSettingsSnapshot(
                        snapshot.hsms().deviceId(),
                        snapshot.eqp().commMode(),
                        snapshot.hsms().t3Timeout(),
                        snapshot.hsms().t5Timeout(),
                        snapshot.hsms().t6Timeout(),
                        snapshot.hsms().t7Timeout(),
                        snapshot.hsms().t8Timeout(),
                        snapshot.hsms().linkTestEnabled(),
                        snapshot.hsms().linkTestInterval(),
                        snapshot.hsms().maxMsgBytes()
                ),
                snapshot.socket() == null ? null : new GatewayEquipmentProfileSnapshot.SocketSettingsSnapshot(
                        snapshot.socket().socketProtocolType(),
                        snapshot.eqp().commMode(),
                        snapshot.socket().charset(),
                        snapshot.socket().heartbeatEnabled(),
                        snapshot.socket().heartbeatInterval(),
                        snapshot.socket().readTimeout(),
                        snapshot.socket().writeTimeout(),
                        snapshot.socket().maxFrameSizeBytes(),
                        snapshot.socket().keepAliveEnabled()
                ),
                snapshot.runtimeState() == null ? null : new GatewayEquipmentProfileSnapshot.CurrentStateSnapshot(
                        snapshot.runtimeState().controlState() == null ? null : snapshot.runtimeState().controlState().name(),
                        snapshot.runtimeState().eqpState() == null ? null : snapshot.runtimeState().eqpState().name(),
                        snapshot.runtimeState().sinceAt() == null ? null : snapshot.runtimeState().sinceAt().toString(),
                        snapshot.runtimeState().reasonCode(),
                        snapshot.runtimeState().reasonDetail(),
                        snapshot.runtimeState().updatedAt() == null ? null : snapshot.runtimeState().updatedAt().toString()
                ),
                snapshot.portStatuses().stream()
                        .map(port -> new GatewayEquipmentProfileSnapshot.PortStatusSnapshot(
                                port.portId(),
                                port.portType() == null ? null : port.portType().name(),
                                port.portState() == null ? null : port.portState().name(),
                                port.carrierId(),
                                port.carrierType() == null ? null : port.carrierType().name(),
                                port.carrierState() == null ? null : port.carrierState().name(),
                                port.updatedAt() == null ? null : port.updatedAt().toString()
                        ))
                        .toList(),
                snapshot.logPolicy() == null ? null : new GatewayEquipmentProfileSnapshot.LogPolicySnapshot(
                        snapshot.logPolicy().logLevel() == null ? null : snapshot.logPolicy().logLevel().name(),
                        snapshot.logPolicy().logRetentionDays(),
                        snapshot.logPolicy().logPath(),
                        snapshot.logPolicy().updatedAt() == null ? null : snapshot.logPolicy().updatedAt().toString()
                ),
                snapshot.params().stream()
                        .map(param -> new GatewayEquipmentProfileSnapshot.ParamSnapshot(
                                param.eqpParamKey(),
                                param.paramName(),
                                param.paramVersion(),
                                param.paramValue(),
                                param.updatedAt() == null ? null : param.updatedAt().toString()
                        ))
                        .toList(),
                snapshot.eqp().updatedAt() == null ? OffsetDateTime.now(ZoneOffset.UTC).toString() : snapshot.eqp().updatedAt().toString()
        );
    }

    private ObjectNode buildEqpParamSnapshot(
            final String appliedParamVersion,
            final List<TcEqpParam> params
    ) {
        final ObjectNode eqpParams = objectMapper.createObjectNode();
        if (appliedParamVersion != null) {
            eqpParams.put("version", appliedParamVersion);
        }
        final var items = eqpParams.putArray("items");
        params.stream()
                .filter(param -> appliedParamVersion == null || Objects.equals(appliedParamVersion, param.paramVersion()))
                .forEach(param -> {
                    final ObjectNode item = items.addObject();
                    item.put("name", param.paramName());
                    item.put("value", param.paramValue());
                });
        return eqpParams;
    }

    private ObjectNode buildJarLocator(
            final String jarFileName,
            final byte[] jarFile
    ) {
        final ObjectNode jarLocator = objectMapper.createObjectNode();
        jarLocator.put("jarFileName", jarFileName);
        jarLocator.put("expectedSha256", sha256Hex(jarFile));
        jarLocator.put("sourceType", "DB");
        return jarLocator;
    }

    private UiCommandEventType parseCompensationEventType(final String eventTypeText) {
        if (eventTypeText == null || eventTypeText.isBlank()) {
            throw new UiBadRequestException("보상 snapshot에 eventType 이 없습니다.");
        }
        return UiCommandEventType.fromText(eventTypeText);
    }

    private GatewayEquipmentProfileSnapshot parseEquipmentProfile(final JsonNode equipmentProfileNode) {
        if (equipmentProfileNode == null || equipmentProfileNode.isNull()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(equipmentProfileNode, GatewayEquipmentProfileSnapshot.class);
        } catch (Exception exception) {
            throw new UiBadRequestException("보상 equipmentProfile 역직렬화에 실패했습니다.");
        }
    }

    private String sha256Hex(final byte[] payload) {
        if (payload == null || payload.length == 0) {
            return "";
        }
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(payload);
            final StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 계산에 실패했습니다.", exception);
        }
    }

    private void copyFields(
            final ObjectNode destination,
            final JsonNode source,
            final String... fieldNames
    ) {
        if (source == null || !source.isObject()) {
            return;
        }
        for (String fieldName : fieldNames) {
            final JsonNode fieldValue = source.get(fieldName);
            if (fieldValue != null) {
                destination.set(fieldName, fieldValue.deepCopy());
            }
        }
    }

    private void copyAllFields(
            final ObjectNode destination,
            final JsonNode source
    ) {
        if (source == null || !source.isObject()) {
            return;
        }
        source.properties().forEach(entry -> {
            final String fieldName = entry.getKey();
            final JsonNode fieldValue = entry.getValue();
            if (fieldValue != null) {
                destination.set(fieldName, fieldValue.deepCopy());
            }
        });
    }

    private String buildTraceId(
            final long deployKey,
            final long deployTargetKey,
            final String stepCode,
            final int attemptNo
    ) {
        return DeployTraceRegistry.DEPLOY_TRACE_PREFIX
                + deployKey
                + "-TARGET-" + deployTargetKey
                + "-STEP-" + stepCode
                + "-ATT-" + attemptNo;
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

    private DeployTargetStatus parseTargetStatus(final String rawStatus) {
        final String normalized = normalizeOptionalText(rawStatus);
        if (normalized == null) {
            return null;
        }
        try {
            return DeployTargetStatus.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new UiBadRequestException("지원하지 않는 targetStatus 입니다. status=" + rawStatus);
        }
    }

    private boolean isTerminal(final DeployStatus deployStatus) {
        return switch (deployStatus) {
            case COMPLETED, PARTIAL_FAILED, FAILED, CANCELED -> true;
            case PLANNING, READY, RUNNING -> false;
        };
    }

    private String resolveResultCode(
            final DeployStatus deployStatus,
            final DeployTargetCounts counts
    ) {
        return switch (deployStatus) {
            case PLANNING, READY -> "DEPLOY_ACCEPTED";
            case RUNNING -> "DEPLOY_RUNNING";
            case COMPLETED -> "DEPLOY_COMPLETED";
            case PARTIAL_FAILED -> counts.compensationFailed() > 0
                    ? "DEPLOY_COMPENSATION_FAILED"
                    : "DEPLOY_PARTIAL_FAILED";
            case FAILED -> "DEPLOY_FAILED";
            case CANCELED -> "DEPLOY_CANCELED";
        };
    }

    private DeployResultMessage resolveSummaryMessage(
            final DeployStatus deployStatus,
            final DeployTargetCounts counts
    ) {
        return switch (deployStatus) {
            case PLANNING, READY -> new DeployResultMessage(
                    "작업이 접수되었습니다.",
                    "설비 " + counts.total() + "대에 대한 작업이 준비 중입니다.",
                    DeployResultSeverity.INFO
            );
            case RUNNING -> new DeployResultMessage(
                    "설비 변경 작업 진행 중",
                    counts.total() + "대 중 " + counts.completed() + "대 완료, "
                            + counts.running() + "대 진행 중입니다.",
                    DeployResultSeverity.INFO
            );
            case COMPLETED -> completedMessage(counts);
            case PARTIAL_FAILED -> counts.compensationFailed() > 0
                    ? new DeployResultMessage(
                    "작업 실패",
                    "일부 설비는 적용 실패 후 자동 복구에도 실패했습니다.",
                    DeployResultSeverity.ERROR
            )
                    : new DeployResultMessage(
                    "일부 설비 적용 실패",
                    counts.total() + "대 중 " + counts.successLikeCount() + "대 성공, "
                            + (counts.failed() + counts.rolledBack()) + "대 실패했습니다.",
                    DeployResultSeverity.WARNING
            );
            case FAILED -> new DeployResultMessage(
                    "작업 실패",
                    counts.total() + "대 중 성공한 설비가 없습니다.",
                    DeployResultSeverity.ERROR
            );
            case CANCELED -> new DeployResultMessage(
                    "작업 취소",
                    "작업이 취소되었습니다.",
                    DeployResultSeverity.WARNING
            );
        };
    }

    private DeployResultMessage completedMessage(final DeployTargetCounts counts) {
        return new DeployResultMessage(
                "작업 완료",
                counts.total() + "대 중 " + counts.successLikeCount() + "대 적용이 완료되었습니다.",
                DeployResultSeverity.SUCCESS
        );
    }

    private static String normalizeActor(final String actor) {
        if (actor == null || actor.isBlank()) {
            return "SYSTEM";
        }
        return actor.trim();
    }

    private static String normalizeOptionalText(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String textValue(
            final JsonNode parent,
            final String fieldName
    ) {
        if (parent == null || !parent.isObject()) {
            return null;
        }
        final JsonNode node = parent.get(fieldName);
        if (node == null || node.isNull()) {
            return null;
        }
        return normalizeOptionalText(node.asString(null));
    }

    private static Integer intValue(
            final JsonNode parent,
            final String fieldName
    ) {
        if (parent == null || !parent.isObject()) {
            return null;
        }
        final JsonNode node = parent.get(fieldName);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.canConvertToInt()) {
            return null;
        }
        return node.intValue();
    }
}
