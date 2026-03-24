package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.nori.tc.messaging.kafka.contract.GatewayEquipmentProfileSnapshot;
import com.nori.tc.ui.core.deploy.DeployResolvedStepPlan;
import com.nori.tc.ui.core.deploy.DeployResultMessage;
import com.nori.tc.ui.core.deploy.DeployResultSeverity;
import com.nori.tc.ui.core.deploy.DeployScenarioAction;
import com.nori.tc.ui.core.deploy.DeploySnapshotKind;
import com.nori.tc.ui.core.deploy.DeploySnapshotScope;
import com.nori.tc.ui.core.deploy.DeployStatus;
import com.nori.tc.ui.core.deploy.DeployTargetCounts;
import com.nori.tc.ui.core.deploy.DeployTargetStatus;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.model.UiCommandMessage;
import com.nori.tc.ui.core.port.db.DeployStorePort;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import com.nori.tc.ui.core.port.messaging.UiBusinessEventPublishPort;
import com.nori.tc.ui.core.port.messaging.UiGatewayEventPublishPort;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DeployService의 메시지/payload/스냅샷/요약 보조 책임을 분리합니다.
 */
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

    DeployServiceCommandSupport(
            final DeployStorePort deployStorePort,
            final EqpCrudPort eqpCrudPort,
            final UiGatewayEventPublishPort gatewayEventPublishPort,
            final UiBusinessEventPublishPort businessEventPublishPort,
            final ObjectMapper objectMapper
    ) {
        this.deployStorePort = Objects.requireNonNull(deployStorePort, "deployStorePort is null");
        this.gatewayEventPublishPort = Objects.requireNonNull(gatewayEventPublishPort, "gatewayEventPublishPort is null");
        this.businessEventPublishPort = Objects.requireNonNull(businessEventPublishPort, "businessEventPublishPort is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
        this.snapshotSupport = new DeployServiceSnapshotSupport(
                Objects.requireNonNull(eqpCrudPort, "eqpCrudPort is null"),
                objectMapper
        );
    }

    void refreshDeployStatus(
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

    void publishCommand(
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

    Set<String> expectedSources(final DeployResolvedStepPlan step) {
        return expectedSources(step.targetApp());
    }

    Set<String> expectedSources(final com.nori.tc.ui.core.deploy.DeployTargetApp targetApp) {
        return switch (targetApp) {
            case GATEWAY -> Set.of(SOURCE_GATEWAY);
            case BUSINESS -> Set.of(SOURCE_BUSINESS);
        };
    }

    JsonNode captureTargetPreState(final PersistedDeployTarget target) {
        final boolean hasRollbackStep = target.planDraft().resolvedSteps().stream()
                .anyMatch(DeployResolvedStepPlan::rollbackEnabled);
        if (!hasRollbackStep) {
            return null;
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
        return snapshot;
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
        return DeployTraceRegistry.DEPLOY_TRACE_PREFIX
                + deployKey
                + "-TARGET-" + deployTargetKey
                + "-STEP-" + stepCode
                + "-ATT-" + attemptNo;
    }

    UiCommandEventType parseCompensationEventType(final String eventTypeText) {
        if (eventTypeText == null || eventTypeText.isBlank()) {
            throw new UiBadRequestException("보상 snapshot에 eventType 이 없습니다.");
        }
        return UiCommandEventType.fromText(eventTypeText);
    }

    GatewayEquipmentProfileSnapshot parseEquipmentProfile(final JsonNode equipmentProfileNode) {
        if (equipmentProfileNode == null || equipmentProfileNode.isNull()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(equipmentProfileNode, GatewayEquipmentProfileSnapshot.class);
        } catch (Exception exception) {
            throw new UiBadRequestException("보상 equipmentProfile 역직렬화에 실패했습니다.");
        }
    }

    DeployTargetStatus parseTargetStatus(final String rawStatus) {
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
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    ThreadFactory namedThreadFactory(final String prefix, final AtomicInteger targetExecutorThreadSequence) {
        return runnable -> {
            final Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            thread.setName(prefix + targetExecutorThreadSequence.incrementAndGet());
            return thread;
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
        if (parent == null || !parent.isObject()) {
            return null;
        }
        final JsonNode node = parent.get(fieldName);
        if (node == null || node.isNull()) {
            return null;
        }
        return normalizeOptionalTextValue(node.asString(null));
    }

    static Integer intValue(
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

    static String normalizeOptionalTextValue(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
