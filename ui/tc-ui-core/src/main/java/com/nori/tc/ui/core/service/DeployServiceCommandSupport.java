package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.nori.tc.common.runtime.support.TraceIdGenerator;
import com.nori.tc.ui.core.deploy.DeployResolvedStepPlan;
import com.nori.tc.ui.core.deploy.DeployResultMessage;
import com.nori.tc.ui.core.deploy.DeployResultSeverity;
import com.nori.tc.ui.core.deploy.DeployScenarioAction;
import com.nori.tc.ui.core.deploy.DeploySnapshotKind;
import com.nori.tc.ui.core.deploy.DeploySnapshotScope;
import com.nori.tc.ui.core.deploy.DeployStatus;
import com.nori.tc.ui.core.deploy.DeploySummarySnapshot;
import com.nori.tc.ui.core.deploy.DeployTargetCounts;
import com.nori.tc.ui.core.deploy.DeployTargetStatus;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.model.UiCommandMessage;
import com.nori.tc.ui.core.model.UiEquipmentProfileSnapshot;
import com.nori.tc.ui.core.port.db.DeployStorePort;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.core.port.db.ModelCrudPort;
import com.nori.tc.ui.core.port.db.ModelDetailQueryPort;
import com.nori.tc.ui.core.port.messaging.UiBusinessEventPublishPort;
import com.nori.tc.ui.core.port.messaging.UiGatewayEventPublishPort;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

/**
 * DeployService의 메시지/payload/스냅샷/요약 보조 책임을 분리합니다.
 */
@Service
final class DeployServiceCommandSupport {

    private static final String UI_SOURCE = "TC-UI-APP";
    private static final String SOURCE_GATEWAY = "TC-GATEWAY-APP";
    private static final String SOURCE_BUSINESS = "TC-BUSINESS-APP";
    private static final String SNAPSHOT_SOURCE_DB = "DB";
    private static final String SNAPSHOT_SOURCE_ORCHESTRATOR = "ORCHESTRATOR";

    private final DeployStorePort deployStorePort;
    private final UiGatewayEventPublishPort gatewayEventPublishPort;
    private final UiBusinessEventPublishPort businessEventPublishPort;
    private final ObjectMapper objectMapper;
    private final DeployServiceSnapshotSupport snapshotSupport;
    private final ModelParamPayloadSupport modelParamPayloadSupport;
    private final EqpParamPayloadSupport eqpParamPayloadSupport;
    private final ModelCrudPort modelCrudPort;

    DeployServiceCommandSupport(
            final DeployStorePort deployStorePort,
            final EqpCrudPort eqpCrudPort,
            final EqpQueryPort eqpQueryPort,
            final ModelCrudPort modelCrudPort,
            final ModelDetailQueryPort modelDetailQueryPort,
            final UiGatewayEventPublishPort gatewayEventPublishPort,
            final UiBusinessEventPublishPort businessEventPublishPort,
            final ObjectMapper objectMapper
    ) {
        this.deployStorePort = Objects.requireNonNull(deployStorePort, "deployStorePort is null");
        this.gatewayEventPublishPort = Objects.requireNonNull(gatewayEventPublishPort, "gatewayEventPublishPort is null");
        this.businessEventPublishPort = Objects.requireNonNull(businessEventPublishPort, "businessEventPublishPort is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
        this.modelCrudPort = Objects.requireNonNull(modelCrudPort, "modelCrudPort is null");
        this.modelParamPayloadSupport = new ModelParamPayloadSupport(
                Objects.requireNonNull(modelDetailQueryPort, "modelDetailQueryPort is null"),
                objectMapper
        );
        this.eqpParamPayloadSupport = new EqpParamPayloadSupport(
                Objects.requireNonNull(eqpQueryPort, "eqpQueryPort is null"),
                objectMapper
        );
        this.snapshotSupport = new DeployServiceSnapshotSupport(
                Objects.requireNonNull(eqpCrudPort, "eqpCrudPort is null"),
                modelDetailQueryPort,
                objectMapper
        );
    }

    void refreshDeployStatus(
            final long deployKey,
            final String lastErrorCode,
            final String lastErrorMessage
    ) {
        final DeploySummarySnapshot currentSummary = deployStorePort.findDeploySummary(deployKey).orElse(null);
        if (currentSummary == null || currentSummary.deployStatus() == DeployStatus.CANCELED) {
            return;
        }

        final DeployTargetCounts counts = deployStorePort.summarizeTargets(deployKey);
        if (counts.running() > 0 || counts.rollingBack() > 0) {
            return;
        }

        if (currentSummary.pauseRequested() && counts.pending() > 0) {
            deployStorePort.updateDeployStatus(
                    deployKey,
                    DeployStatus.READY,
                    false,
                    normalizeOptionalText(lastErrorCode),
                    normalizeOptionalText(lastErrorMessage)
            );
            return;
        }

        if (counts.pending() > 0) {
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
                false,
                normalizeOptionalText(lastErrorCode),
                normalizeOptionalText(lastErrorMessage)
        );
    }

    void publishStepCommand(
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
                target.planDraft().eqpId(),
                step,
                traceId,
                actor,
                target.planDraft().desiredState().mergedUiMessage()
        );
        final UiEquipmentProfileSnapshot equipmentProfile =
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

    void publishCommand(
            final long deployKey,
            final com.nori.tc.ui.core.deploy.DeployTargetApp targetApp,
            final UiCommandEventType eventType,
            final PersistedDeployTarget target,
            final String interfaceType,
            final JsonNode uiMessage,
            final UiEquipmentProfileSnapshot equipmentProfile,
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

    void publishDirectCommand(
            final com.nori.tc.ui.core.deploy.DeployTargetApp targetApp,
            final UiCommandEventType eventType,
            final String eqpId,
            final String interfaceType,
            final JsonNode uiMessage,
            final UiEquipmentProfileSnapshot equipmentProfile,
            final String traceId
    ) {
        publishDirectCommand(targetApp, eventType, eqpId, interfaceType, uiMessage, equipmentProfile, traceId, null);
    }

    /**
     * publishDirectCommand 의 partition 명시 오버로드.
     *
     * <p>{@code explicitGatewayPartition} 이 null 이면 기존 동작 (Gateway 측 publisher 가 DB lookup 으로 partition 결정).
     * 비-null 이면 해당 partition 으로 publishToPartition. EQP_UPDATE_PARTITION_MOVE 시나리오의
     * UPSERT_GATEWAY_CONTEXT_OLD_PARTITION / NEW_PARTITION step 이 각각 OLD / NEW partition 을 명시 전달합니다.</p>
     */
    void publishDirectCommand(
            final com.nori.tc.ui.core.deploy.DeployTargetApp targetApp,
            final UiCommandEventType eventType,
            final String eqpId,
            final String interfaceType,
            final JsonNode uiMessage,
            final UiEquipmentProfileSnapshot equipmentProfile,
            final String traceId,
            final Integer explicitGatewayPartition
    ) {
        final UiCommandMessage message = new UiCommandMessage(
                eventType,
                traceId,
                UI_SOURCE,
                eqpId,
                interfaceType,
                uiMessage == null ? objectMapper.createObjectNode() : uiMessage,
                equipmentProfile
        );

        if (targetApp == com.nori.tc.ui.core.deploy.DeployTargetApp.GATEWAY) {
            if (explicitGatewayPartition != null) {
                gatewayEventPublishPort.publishToPartition(message, explicitGatewayPartition);
            } else {
                gatewayEventPublishPort.publish(message);
            }
            return;
        }
        businessEventPublishPort.publish(message);
    }

    Set<String> expectedSources(final DeployResolvedStepPlan step) {
        return expectedSources(step.targetApp());
    }

    Set<String> expectedSources(final com.nori.tc.ui.core.deploy.DeployTargetApp targetApp) {
        return switch (targetApp) {
            case GATEWAY -> Set.of(SOURCE_GATEWAY);
            case BUSINESS -> Set.of(SOURCE_BUSINESS);
        };
    }

    Optional<JsonNode> captureTargetPreState(final PersistedDeployTarget target) {
        final boolean hasRollbackStep = target.planDraft().resolvedSteps().stream()
                .anyMatch(DeployResolvedStepPlan::rollbackEnabled);
        if (!hasRollbackStep) {
            return Optional.empty();
        }

        final JsonNode snapshot = snapshotSupport.buildTargetPreStateSnapshot(target);
        deployStorePort.saveTargetSnapshot(
                target.deployTargetKey(),
                DeploySnapshotScope.TARGET_PRE_STATE,
                null,
                DeploySnapshotKind.TARGET_FULL_STATE,
                SNAPSHOT_SOURCE_DB,
                snapshot
        );
        return Optional.of(snapshot);
    }

    void saveCompensationSnapshot(
            final PersistedDeployTarget target,
            final DeployResolvedStepPlan step,
            final JsonNode targetPreState
    ) {
        final JsonNode compensationSnapshot = snapshotSupport.buildCompensationSnapshot(target, step, targetPreState);
        deployStorePort.saveTargetSnapshot(
                target.deployTargetKey(),
                DeploySnapshotScope.STEP_PRE_STATE,
                step.code(),
                DeploySnapshotKind.COMPENSATION_COMMAND,
                SNAPSHOT_SOURCE_ORCHESTRATOR,
                compensationSnapshot
        );
    }

    String buildTraceId(
            final long deployKey,
            final long deployTargetKey,
            final String stepCode,
            final int attemptNo
    ) {
        return TraceIdGenerator.newTraceId();
    }

    UiCommandEventType parseCompensationEventType(final String eventTypeText) {
        if (eventTypeText == null || eventTypeText.isBlank()) {
            throw new UiBadRequestException("보상 snapshot에 eventType 이 없습니다.");
        }
        return UiCommandEventType.fromText(eventTypeText);
    }

    UiEquipmentProfileSnapshot parseEquipmentProfile(final JsonNode equipmentProfileNode) {
        return optionalEquipmentProfile(equipmentProfileNode).orElse(null);
    }

    DeployTargetStatus parseTargetStatus(final String rawStatus) {
        return normalizedOptionalText(rawStatus)
                .map(normalized -> {
                    try {
                        return DeployTargetStatus.valueOf(normalized.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException exception) {
                        throw new UiBadRequestException("지원하지 않는 targetStatus 입니다. status=" + rawStatus);
                    }
                })
                .orElse(null);
    }

    boolean isTerminal(final DeployStatus deployStatus) {
        return switch (deployStatus) {
            case COMPLETED, PARTIAL_FAILED, FAILED, CANCELED -> true;
            case PLANNING, READY, RUNNING -> false;
        };
    }

    String resolveResultCode(
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

    DeployResultMessage resolveSummaryMessage(
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

    DeployResultMessage completedMessage(final DeployTargetCounts counts) {
        return new DeployResultMessage(
                "작업 완료",
                counts.total() + "대 중 " + counts.successLikeCount() + "대 적용이 완료되었습니다.",
                DeployResultSeverity.SUCCESS
        );
    }

    String normalizeActor(final String actor) {
        if (actor == null || actor.isBlank()) {
            return "SYSTEM";
        }
        return actor.trim();
    }

    String normalizeOptionalText(final String value) {
        return normalizedOptionalText(value).orElse(null);
    }

    ThreadFactory namedThreadFactory(final String prefix, final AtomicInteger targetExecutorThreadSequence) {
        return runnable -> {
            final Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            thread.setName(prefix + targetExecutorThreadSequence.incrementAndGet());
            return thread;
        };
    }

    UiCommandEventType resolveMessageEventType(
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
            case RELOAD_BUSINESS_MODEL_RUNTIME,
                 UPDATE_MODEL_BINDING -> switch (deployEventType) {
                case EQP_CREATE -> UiCommandEventType.EQP_CREATE;
                case EQP_UPDATE,
                     EQP_GATEWAY_JARFILE_UPDATE,
                     EQP_BUSINESS_JARFILE_UPDATE,
                     EQP_REGISTER_DEPLOY -> UiCommandEventType.EQP_MODEL_UPDATE;
                default -> UiCommandEventType.EQP_MODEL_UPDATE;
            };
            case UPSERT_GATEWAY_CONTEXT,
                 REBUILD_GATEWAY_RUNTIME -> switch (deployEventType) {
                case EQP_CREATE -> UiCommandEventType.EQP_CREATE;
                case EQP_UPDATE,
                     EQP_GATEWAY_JARFILE_UPDATE,
                     EQP_BUSINESS_JARFILE_UPDATE,
                     EQP_REGISTER_DEPLOY -> UiCommandEventType.EQP_UPDATE;
                default -> UiCommandEventType.EQP_UPDATE;
            };
            // routePartition 이동 시나리오의 두 단계는 일반 EQP_UPDATE 이벤트 타입을 사용합니다.
            // (옛 owner / 새 owner 구분은 publish target partition 으로만 분리하고 메시지 의미는 동일)
            case UPSERT_GATEWAY_CONTEXT_OLD_PARTITION,
                 UPSERT_GATEWAY_CONTEXT_NEW_PARTITION -> UiCommandEventType.EQP_UPDATE;
            // Business runtime cache 메타데이터(routePartition 등) 갱신은 EQP_UPDATE 이벤트로 Business에 전달되어
            // BusinessUiTaskProcessorRegistry의 EQP_UPDATE handler가 BusinessModelRuntimeCache를 갱신합니다.
            case UPDATE_BUSINESS_RUNTIME_METADATA -> UiCommandEventType.EQP_UPDATE;
            // Gateway equipment context(routePartition 등) 갱신도 EQP_UPDATE 이벤트로 Gateway에 전달되어
            // GatewayUiTaskProcessorRegistry의 EQP_UPDATE handler가 createOrUpdateContext 로 GatewayEquipmentInfo를 다시 적재합니다.
            case UPDATE_GATEWAY_RUNTIME_METADATA -> UiCommandEventType.EQP_UPDATE;
            case RESTORE_PREVIOUS_MODEL_BINDING,
                 RESTORE_PREVIOUS_BUSINESS_RUNTIME,
                 RESTORE_PREVIOUS_BUSINESS_PLUGIN,
                 RESTORE_PREVIOUS_EQP_PARAM,
                 RESTORE_PREVIOUS_GATEWAY_CONTEXT,
                 RESTORE_PREVIOUS_GATEWAY_RUNTIME,
                 RESTORE_PREVIOUS_GATEWAY_PLUGIN,
                 RESTORE_PREVIOUS_BUSINESS_BINDING,
                 RESTORE_PREVIOUS_BUSINESS_RUNTIME_METADATA,
                 RESTORE_PREVIOUS_GATEWAY_RUNTIME_METADATA ->
                    throw new UiBadRequestException("rollback action은 T4에서 직접 실행하지 않습니다. action=" + action);
        };
    }

    JsonNode buildDirectStepUiMessage(
            final String executionId,
            final UiCommandEventType eventType,
            final String eqpId,
            final DeployResolvedStepPlan step,
            final String traceId,
            final String actor,
            final JsonNode mergedUiMessage
    ) {
        final ObjectNode root = objectMapper.createObjectNode();
        copyCommonDeployPayload(root, mergedUiMessage, step.action(), eqpId);

        final ObjectNode orchestration = root.putObject("_direct");
        orchestration.put("executionId", executionId);
        orchestration.put("eventType", eventType.name());
        orchestration.put("targetApp", step.targetApp().name());
        orchestration.put("stepCode", step.code());
        orchestration.put("stepAction", step.action().name());
        orchestration.put("traceId", traceId);
        orchestration.put("requestedBy", actor);

        return root;
    }

    private JsonNode buildStepUiMessage(
            final long deployKey,
            final long deployTargetKey,
            final String eqpId,
            final DeployResolvedStepPlan step,
            final String traceId,
            final String actor,
            final JsonNode mergedUiMessage
    ) {
        final ObjectNode root = objectMapper.createObjectNode();
        copyCommonDeployPayload(root, mergedUiMessage, step.action(), eqpId);

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
            final DeployScenarioAction action,
            final String eqpId
    ) {
        switch (action) {
            case UPSERT_GATEWAY_CONTEXT,
                 UPSERT_GATEWAY_CONTEXT_OLD_PARTITION,
                 UPSERT_GATEWAY_CONTEXT_NEW_PARTITION -> copyFields(destination, mergedUiMessage, "gatewayProfile");
            case RELOAD_BUSINESS_PLUGIN -> copyFields(destination, mergedUiMessage, "modelInfo", "businessJar");
            case RELOAD_BUSINESS_MODEL_RUNTIME -> {
                copyFields(destination, mergedUiMessage, "modelInfo");
                enrichModelParamsPayload(destination, mergedUiMessage);
            }
            case UPDATE_MODEL_BINDING -> {
                copyFields(destination, mergedUiMessage, "modelInfo", "businessJar");
                enrichModelParamsPayload(destination, mergedUiMessage);
            }
            case UPDATE_EQP_PARAM -> enrichEqpParamsPayload(destination, mergedUiMessage, eqpId);
            case RELOAD_GATEWAY_PLUGIN -> copyFields(destination, mergedUiMessage, "gatewayJar");
            case REBUILD_GATEWAY_RUNTIME -> copyFields(destination, mergedUiMessage, "gatewayProfile", "gatewayJar");
            case RECONNECT_GATEWAY -> copyFields(destination, mergedUiMessage, "gatewayProfile");
            case START_GATEWAY_RUNTIME, END_GATEWAY_RUNTIME, END_EQP_IF_RUNNING ->
                    destination.put("reason", "DEPLOY_REQUEST");
            case DELETE_GATEWAY_MEMORY, DELETE_BUSINESS_BINDING -> {
                destination.put("deleteMode", "SAFE");
                copyFields(destination, mergedUiMessage, "modelInfo", "businessJar");
            }
            // Business runtime metadata 갱신은 routePartition을 포함한 gatewayProfile 정보를 그대로 전달합니다.
            // Business는 equipmentProfile.routePartition을 정본으로 사용해 cache를 갱신합니다.
            case UPDATE_BUSINESS_RUNTIME_METADATA -> copyFields(destination, mergedUiMessage, "gatewayProfile");
            // Gateway runtime metadata 갱신도 동일하게 gatewayProfile 정보를 그대로 전달합니다.
            // Gateway는 EQP_UPDATE 처리 시 createOrUpdateContext에서 DB를 다시 읽어 GatewayEquipmentInfo를 적재합니다.
            case UPDATE_GATEWAY_RUNTIME_METADATA -> copyFields(destination, mergedUiMessage, "gatewayProfile");
            case SEND_MESSAGE -> copyAllFields(destination, mergedUiMessage);
            case RESTORE_PREVIOUS_MODEL_BINDING,
                 RESTORE_PREVIOUS_BUSINESS_RUNTIME,
                 RESTORE_PREVIOUS_BUSINESS_PLUGIN,
                 RESTORE_PREVIOUS_EQP_PARAM,
                 RESTORE_PREVIOUS_GATEWAY_CONTEXT,
                 RESTORE_PREVIOUS_GATEWAY_RUNTIME,
                 RESTORE_PREVIOUS_GATEWAY_PLUGIN,
                 RESTORE_PREVIOUS_BUSINESS_BINDING,
                 RESTORE_PREVIOUS_BUSINESS_RUNTIME_METADATA,
                 RESTORE_PREVIOUS_GATEWAY_RUNTIME_METADATA ->
                    throw new UiBadRequestException("rollback payload는 T4에서 생성하지 않습니다. action=" + action);
        }
    }

    private void enrichModelParamsPayload(final ObjectNode destination, final JsonNode mergedUiMessage) {
        final Long modelVersionKey = longValue(
                mergedUiMessage == null ? null : mergedUiMessage.path("modelInfo"),
                "modelVersionKey"
        );
        if (modelVersionKey == null || modelVersionKey <= 0L) {
            return;
        }
        destination.set(
                "modelParams",
                modelParamPayloadSupport.ensureModelParamsPayload(
                        mergedUiMessage == null ? null : mergedUiMessage.get("modelParams"),
                        modelVersionKey
                )
        );
    }

    private void enrichEqpParamsPayload(
            final ObjectNode destination,
            final JsonNode mergedUiMessage,
            final String eqpId
    ) {
        final String version = textValue(
                mergedUiMessage == null ? null : mergedUiMessage.path("eqpParams"),
                "version"
        );
        if (eqpId == null || eqpId.isBlank() || version == null || version.isBlank()) {
            copyFields(destination, mergedUiMessage, "eqpParams");
            return;
        }
        destination.set(
                "eqpParams",
                eqpParamPayloadSupport.ensureEqpParamsPayload(
                        mergedUiMessage == null ? null : mergedUiMessage.get("eqpParams"),
                        eqpId,
                        version
                )
        );
    }

    UiEquipmentProfileSnapshot buildDirectEquipmentProfile(
            final UiCommandEventType messageEventType,
            final String eqpId,
            final String interfaceType,
            final Long eqpKey,
            final JsonNode stepUiMessage
    ) {
        if (messageEventType != UiCommandEventType.EQP_CREATE
                && messageEventType != UiCommandEventType.EQP_UPDATE
                && messageEventType != UiCommandEventType.EQP_MODEL_UPDATE) {
            return Optional.<UiEquipmentProfileSnapshot>empty().orElse(null);
        }
        final JsonNode gatewayProfile = stepUiMessage.get("gatewayProfile");
        if (gatewayProfile == null || !gatewayProfile.isObject()) {
            return Optional.<UiEquipmentProfileSnapshot>empty().orElse(null);
        }
        final JsonNode modelInfo = stepUiMessage == null ? null : stepUiMessage.path("modelInfo");
        final Long resolvedModelVersionKey = longValue(modelInfo, "modelVersionKey");

        return new UiEquipmentProfileSnapshot(
                eqpKey,
                eqpId,
                interfaceType,
                textValue(gatewayProfile, "socketType"),
                intValue(gatewayProfile, "hsmsDeviceId"),
                textValue(gatewayProfile, "eqpIp"),
                intValue(gatewayProfile, "eqpPort"),
                resolveModelKey(modelInfo, resolvedModelVersionKey, null),
                longValue(stepUiMessage.path("gatewayJar"), "jarRef"),
                longValue(stepUiMessage.path("businessJar"), "jarRef"),
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

    UiEquipmentProfileSnapshot buildDirectEquipmentProfile(
            final UiCommandEventType messageEventType,
            final EqpManagementSnapshot executionSnapshot,
            final JsonNode stepUiMessage
    ) {
        if (messageEventType != UiCommandEventType.EQP_CREATE
                && messageEventType != UiCommandEventType.EQP_UPDATE
                && messageEventType != UiCommandEventType.EQP_MODEL_UPDATE) {
            return null;
        }
        if (executionSnapshot == null) {
            return null;
        }

        final OffsetDateTime updatedAt = executionSnapshot.eqp().updatedAt() == null
                ? OffsetDateTime.now(ZoneOffset.UTC)
                : executionSnapshot.eqp().updatedAt().withOffsetSameInstant(ZoneOffset.UTC);
        final JsonNode gatewayProfile = stepUiMessage == null ? null : stepUiMessage.get("gatewayProfile");
        final JsonNode eqpParamsNode = stepUiMessage == null ? null : stepUiMessage.path("eqpParams");
        final JsonNode modelInfo = stepUiMessage == null ? null : stepUiMessage.path("modelInfo");
        final Long resolvedModelVersionKey = longValue(modelInfo, "modelVersionKey") != null
                ? longValue(modelInfo, "modelVersionKey")
                : executionSnapshot.eqp().modelVersionKey();
        final Long resolvedModelKey = resolveModelKey(
                modelInfo,
                resolvedModelVersionKey,
                executionSnapshot.model() == null ? null : executionSnapshot.model().modelKey()
        );
        final String resolvedConnectionMode = textValue(gatewayProfile, "connectionMode") != null
                ? textValue(gatewayProfile, "connectionMode")
                : executionSnapshot.eqp().commMode();
        final String resolvedEqpIp = textValue(gatewayProfile, "eqpIp") != null
                ? textValue(gatewayProfile, "eqpIp")
                : executionSnapshot.eqp().eqpIp();
        final Integer resolvedEqpPort = intValue(gatewayProfile, "eqpPort") != null
                ? intValue(gatewayProfile, "eqpPort")
                : executionSnapshot.eqp().eqpPort();
        final String resolvedSocketType = textValue(gatewayProfile, "socketType") != null
                ? textValue(gatewayProfile, "socketType")
                : executionSnapshot.socket() == null ? null : executionSnapshot.socket().socketProtocolType();
        final Integer resolvedHsmsDeviceId = intValue(gatewayProfile, "hsmsDeviceId") != null
                ? intValue(gatewayProfile, "hsmsDeviceId")
                : executionSnapshot.hsms() == null ? null : executionSnapshot.hsms().deviceId();
        final List<UiEquipmentProfileSnapshot.ParamSnapshot> params = resolveEquipmentProfileParams(
                executionSnapshot,
                eqpParamsNode
        );

        final UiEquipmentProfileSnapshot.HsmsSettingsSnapshot hsmsSettings =
                executionSnapshot.hsms() == null ? null : new UiEquipmentProfileSnapshot.HsmsSettingsSnapshot(
                        resolvedHsmsDeviceId,
                        resolvedConnectionMode,
                        executionSnapshot.hsms().t3Timeout(),
                        executionSnapshot.hsms().t5Timeout(),
                        executionSnapshot.hsms().t6Timeout(),
                        executionSnapshot.hsms().t7Timeout(),
                        executionSnapshot.hsms().t8Timeout(),
                        executionSnapshot.hsms().linkTestEnabled(),
                        executionSnapshot.hsms().linkTestInterval(),
                        executionSnapshot.hsms().maxMsgBytes()
                );
        final UiEquipmentProfileSnapshot.SocketSettingsSnapshot socketSettings =
                executionSnapshot.socket() == null ? null : new UiEquipmentProfileSnapshot.SocketSettingsSnapshot(
                        resolvedSocketType,
                        resolvedConnectionMode,
                        executionSnapshot.socket().charset(),
                        executionSnapshot.socket().heartbeatEnabled(),
                        executionSnapshot.socket().heartbeatInterval(),
                        executionSnapshot.socket().readTimeout(),
                        executionSnapshot.socket().writeTimeout(),
                        executionSnapshot.socket().maxFrameSizeBytes(),
                        executionSnapshot.socket().keepAliveEnabled()
                );
        final UiEquipmentProfileSnapshot.CurrentStateSnapshot currentStateSnapshot =
                executionSnapshot.runtimeState() == null ? null : new UiEquipmentProfileSnapshot.CurrentStateSnapshot(
                        executionSnapshot.runtimeState().controlState() == null ? null : executionSnapshot.runtimeState().controlState().name(),
                        executionSnapshot.runtimeState().eqpState() == null ? null : executionSnapshot.runtimeState().eqpState().name(),
                        executionSnapshot.runtimeState().sinceAt() == null ? null : executionSnapshot.runtimeState().sinceAt().toString(),
                        executionSnapshot.runtimeState().reasonCode(),
                        executionSnapshot.runtimeState().reasonDetail(),
                        executionSnapshot.runtimeState().updatedAt() == null ? null : executionSnapshot.runtimeState().updatedAt().toString()
                );
        final List<UiEquipmentProfileSnapshot.PortStatusSnapshot> portStatuses = executionSnapshot.portStatuses().stream()
                .map(port -> new UiEquipmentProfileSnapshot.PortStatusSnapshot(
                        port.portId(),
                        port.portType() == null ? null : port.portType().name(),
                        port.portState(),
                        port.carrierId(),
                        port.carrierType() == null ? null : port.carrierType().name(),
                        port.carrierState() == null ? null : port.carrierState().name(),
                        port.updatedAt() == null ? null : port.updatedAt().toString()
                ))
                .toList();
        final UiEquipmentProfileSnapshot.LogPolicySnapshot logPolicy =
                executionSnapshot.logPolicy() == null ? null : new UiEquipmentProfileSnapshot.LogPolicySnapshot(
                        executionSnapshot.logPolicy().logLevel() == null ? null : executionSnapshot.logPolicy().logLevel().name(),
                        executionSnapshot.logPolicy().logRetentionDays(),
                        executionSnapshot.logPolicy().logPath(),
                        executionSnapshot.logPolicy().updatedAt() == null ? null : executionSnapshot.logPolicy().updatedAt().toString()
                );
        final Long gatewayJarRefFromStep = longValue(stepUiMessage.path("gatewayJar"), "jarRef");
        final Long businessJarRefFromStep = longValue(stepUiMessage.path("businessJar"), "jarRef");
        return new UiEquipmentProfileSnapshot(
                executionSnapshot.eqp().eqpKey(),
                executionSnapshot.eqp().eqpId(),
                executionSnapshot.eqp().commInterface().name(),
                resolvedSocketType,
                resolvedHsmsDeviceId,
                resolvedEqpIp,
                resolvedEqpPort,
                resolvedModelKey,
                gatewayJarRefFromStep != null ? gatewayJarRefFromStep : executionSnapshot.eqp().gatewayJarKey(),
                businessJarRefFromStep != null ? businessJarRefFromStep : executionSnapshot.eqp().businessJarKey(),
                resolvedConnectionMode,
                executionSnapshot.eqp().routePartition(),
                executionSnapshot.eqp().enabled(),
                hsmsSettings,
                socketSettings,
                currentStateSnapshot,
                portStatuses,
                logPolicy,
                params,
                updatedAt.toString()
        );
    }

    private UiEquipmentProfileSnapshot buildEquipmentProfile(
            final UiCommandEventType messageEventType,
            final PersistedDeployTarget target,
            final JsonNode stepUiMessage
    ) {
        if (messageEventType != UiCommandEventType.EQP_CREATE
                && messageEventType != UiCommandEventType.EQP_UPDATE
                && messageEventType != UiCommandEventType.EQP_MODEL_UPDATE) {
            return Optional.<UiEquipmentProfileSnapshot>empty().orElse(null);
        }
        final JsonNode mergedUiMessage = target.planDraft().desiredState() == null
                ? null
                : target.planDraft().desiredState().mergedUiMessage();
        final JsonNode equipmentProfileNode = mergedUiMessage == null ? null : mergedUiMessage.get("equipmentProfile");
        if (equipmentProfileNode == null || !equipmentProfileNode.isObject()) {
            throw new UiBadRequestException(
                    "deploy equipmentProfile payload가 필요합니다. eqpId=" + target.planDraft().eqpId()
                            + ", eventType=" + messageEventType.name()
            );
        }
        return parseEquipmentProfile(equipmentProfileNode);
    }

    private List<UiEquipmentProfileSnapshot.ParamSnapshot> resolveEquipmentProfileParams(
            final EqpManagementSnapshot executionSnapshot,
            final JsonNode eqpParamsNode
    ) {
        if (eqpParamsNode == null || eqpParamsNode.isMissingNode() || eqpParamsNode.isNull()) {
            return executionSnapshot.params().stream()
                    .map(param -> new UiEquipmentProfileSnapshot.ParamSnapshot(
                            param.eqpParamKey(),
                            param.paramName(),
                            param.paramVersion(),
                            param.paramValue(),
                            param.updatedAt() == null ? null : param.updatedAt().toString()
                    ))
                    .toList();
        }

        final JsonNode itemsNode = eqpParamsNode.path("items");
        if (!itemsNode.isArray()) {
            return executionSnapshot.params().stream()
                    .map(param -> new UiEquipmentProfileSnapshot.ParamSnapshot(
                            param.eqpParamKey(),
                            param.paramName(),
                            param.paramVersion(),
                            param.paramValue(),
                            param.updatedAt() == null ? null : param.updatedAt().toString()
                    ))
                    .toList();
        }

        final String version = textValue(eqpParamsNode, "version");
        return StreamSupport.stream(itemsNode.spliterator(), false)
                .map(item -> new UiEquipmentProfileSnapshot.ParamSnapshot(
                        null,
                        textValue(item, "name"),
                        version,
                        item.path("value").asText(""),
                        null
                ))
                .filter(param -> param.paramName() != null && !param.paramName().isBlank())
                .toList();
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

    static void copyFields(
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

    static void copyAllFields(
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

    static String textValue(
            final JsonNode parent,
            final String fieldName
    ) {
        return optionalTextValue(parent, fieldName).orElse(null);
    }

    static Integer intValue(
            final JsonNode parent,
            final String fieldName
    ) {
        return optionalIntValue(parent, fieldName).orElse(null);
    }

    static String normalizeOptionalTextValue(final String value) {
        return normalizedOptionalText(value).orElse(null);
    }

    private Optional<UiEquipmentProfileSnapshot> optionalEquipmentProfile(final JsonNode equipmentProfileNode) {
        if (equipmentProfileNode == null || equipmentProfileNode.isNull()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.treeToValue(equipmentProfileNode, UiEquipmentProfileSnapshot.class));
        } catch (Exception exception) {
            throw new UiBadRequestException("보상 equipmentProfile 역직렬화에 실패했습니다.");
        }
    }

    private Long resolveModelKey(
            final JsonNode modelInfo,
            final Long modelVersionKey,
            final Long fallbackModelKey
    ) {
        final Long explicitModelKey = longValue(modelInfo, "modelKey");
        if (explicitModelKey != null) {
            return explicitModelKey;
        }
        if (modelVersionKey != null) {
            return modelCrudPort.findByModelVersionKey(modelVersionKey)
                    .map(model -> model.modelKey())
                    .orElse(fallbackModelKey);
        }
        return fallbackModelKey;
    }

    private static Long longValue(final JsonNode node, final String fieldName) {
        if (node == null || node.isNull() || fieldName == null) {
            return null;
        }
        final JsonNode field = node.path(fieldName);
        if (field.isNumber()) {
            final long value = field.asLong();
            return value > 0L ? value : null;
        }
        final String text = textValue(node, fieldName);
        if (text == null) {
            return null;
        }
        try {
            final long parsed = Long.parseLong(text);
            return parsed > 0L ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Optional<String> optionalTextValue(
            final JsonNode parent,
            final String fieldName
    ) {
        if (parent == null || !parent.isObject()) {
            return Optional.empty();
        }
        final JsonNode node = parent.get(fieldName);
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        return normalizedOptionalText(node.asString(null));
    }

    private static Optional<Integer> optionalIntValue(
            final JsonNode parent,
            final String fieldName
    ) {
        if (parent == null || !parent.isObject()) {
            return Optional.empty();
        }
        final JsonNode node = parent.get(fieldName);
        if (node == null || node.isNull() || !node.canConvertToInt()) {
            return Optional.empty();
        }
        return Optional.of(node.intValue());
    }

    private static Optional<String> normalizedOptionalText(final String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.trim());
    }
}
