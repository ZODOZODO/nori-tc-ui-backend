package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.nori.tc.messaging.kafka.contract.GatewayEquipmentProfileSnapshot;
import com.nori.tc.ui.core.deploy.DeployResolvedStepPlan;
import com.nori.tc.ui.core.deploy.DeployRollbackMode;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import com.nori.tc.ui.domain.eqp.UiEqpParam;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deploy 전/후 상태 스냅샷과 보상 payload 생성을 분리합니다.
 *
 * <p>DeployServiceCommandSupport는 메시지 publish/orchestration만 담당하고,
 * 이 클래스는 DB snapshot 캡처와 rollback payload 구성 책임만 맡습니다.</p>
 */
final class DeployServiceSnapshotSupport {

    private final EqpCrudPort eqpCrudPort;
    private final ObjectMapper objectMapper;

    DeployServiceSnapshotSupport(
            final EqpCrudPort eqpCrudPort,
            final ObjectMapper objectMapper
    ) {
        this.eqpCrudPort = Objects.requireNonNull(eqpCrudPort, "eqpCrudPort is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
    }

    JsonNode buildTargetPreStateSnapshot(final PersistedDeployTarget target) {
        if (target.planDraft().currentState() == null) {
            return buildTargetPreStateMarker(target.planDraft().eqpId(), target.planDraft().interfaceType(), false);
        }

        final Optional<EqpManagementSnapshot> snapshot = eqpCrudPort.findSnapshotByEqpId(target.planDraft().eqpId());
        if (snapshot.isPresent()) {
            return sanitizeTargetPreState(snapshot.get());
        }

        return buildFallbackTargetPreState(target);
    }

    JsonNode buildCompensationSnapshot(
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

    private JsonNode buildGatewayUpdateCompensationSnapshot(
            final JsonNode targetPreState,
            final long timeoutMs,
            final boolean includeGatewayJar
    ) {
        final ObjectNode uiMessage = objectMapper.createObjectNode();
        DeployServiceCommandSupport.copyFields(uiMessage, targetPreState, "gatewayProfile");
        if (includeGatewayJar) {
            DeployServiceCommandSupport.copyFields(uiMessage, targetPreState, "gatewayJar");
        }
        return buildCommandSnapshot(
                UiCommandEventType.EQP_UPDATE,
                DeployServiceCommandSupport.textValue(targetPreState, "interfaceType"),
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
        DeployServiceCommandSupport.copyFields(uiMessage, targetPreState, "modelInfo");
        if (includeBusinessJar) {
            DeployServiceCommandSupport.copyFields(uiMessage, targetPreState, "businessJar");
        }
        if (includeEqpParams) {
            DeployServiceCommandSupport.copyFields(uiMessage, targetPreState, "eqpParams");
        }
        return buildCommandSnapshot(
                UiCommandEventType.EQP_UPDATE,
                DeployServiceCommandSupport.textValue(targetPreState, "interfaceType"),
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
        DeployServiceCommandSupport.copyFields(uiMessage, targetPreState, "modelInfo", "businessJar");
        return buildCommandSnapshot(
                UiCommandEventType.EQP_BUSINESS_JARFILE_UPDATE,
                DeployServiceCommandSupport.textValue(targetPreState, "interfaceType"),
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
        DeployServiceCommandSupport.copyFields(uiMessage, targetPreState, "gatewayJar");
        return buildCommandSnapshot(
                UiCommandEventType.EQP_GATEWAY_JARFILE_UPDATE,
                DeployServiceCommandSupport.textValue(targetPreState, "interfaceType"),
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
        DeployServiceCommandSupport.copyFields(uiMessage, targetPreState, "eqpParams");
        return buildCommandSnapshot(
                UiCommandEventType.EQP_PARAMETER_UPDATE,
                DeployServiceCommandSupport.textValue(targetPreState, "interfaceType"),
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
                                port.portState(),
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
            final List<UiEqpParam> params
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
}
