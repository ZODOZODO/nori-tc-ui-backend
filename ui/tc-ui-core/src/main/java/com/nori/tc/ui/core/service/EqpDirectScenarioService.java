package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.nori.tc.common.runtime.support.TraceIdGenerator;
import com.nori.tc.ui.core.deploy.DeployTargetPlanDraft;
import com.nori.tc.ui.core.eqp.EqpCommandResult;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.port.db.DemoJobCleanupPort;
import com.nori.tc.ui.core.port.db.ModelDetailQueryPort;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * direct EQP event를 공통 scenario planner/runner로 실행하는 facade입니다.
 */
@Service
public class EqpDirectScenarioService {

    private static final Logger log = LoggerFactory.getLogger(EqpDirectScenarioService.class);

    private static final int BAD_REQUEST_STATUS = 400;
    private static final int NOT_FOUND_STATUS = 404;
    private static final int CONFLICT_STATUS = 409;
    private static final int INTERNAL_ERROR_STATUS = 500;

    private final EqpPersistenceService persistenceService;
    private final EqpManagementCommandValidator validator;
    private final EqpDirectScenarioPlanner planner;
    private final EqpDirectScenarioLocalMutationCoordinator localMutationCoordinator;
    private final EqpDirectScenarioRunner runner;
    private final DemoJobCleanupPort demoJobCleanupPort;
    private final ObjectMapper objectMapper;
    private final ModelParamPayloadSupport modelParamPayloadSupport;

    public EqpDirectScenarioService(
            final EqpPersistenceService persistenceService,
            final EqpManagementCommandValidator validator,
            final EqpDirectScenarioPlanner planner,
            final EqpDirectScenarioLocalMutationCoordinator localMutationCoordinator,
            final EqpDirectScenarioRunner runner,
            final DemoJobCleanupPort demoJobCleanupPort,
            final ModelDetailQueryPort modelDetailQueryPort,
            final ObjectMapper objectMapper
    ) {
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService is null");
        this.validator = Objects.requireNonNull(validator, "validator is null");
        this.planner = Objects.requireNonNull(planner, "planner is null");
        this.localMutationCoordinator = Objects.requireNonNull(localMutationCoordinator, "localMutationCoordinator is null");
        this.runner = Objects.requireNonNull(runner, "runner is null");
        this.demoJobCleanupPort = Objects.requireNonNull(demoJobCleanupPort, "demoJobCleanupPort is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
        this.modelParamPayloadSupport = new ModelParamPayloadSupport(
                Objects.requireNonNull(modelDetailQueryPort, "modelDetailQueryPort is null"),
                objectMapper
        );
    }

    public EqpCommandResult executeCreate(final EqpManagementCommand.Create command) {
        try {
            validator.validateCreateCommand(command);
            validator.validateModelBinding(command.interfaceType(), command.isDev(), command.modelVersionKey());
            validator.validateCreateTargetAbsent(
                    persistenceService.findSnapshotByEqpId(command.eqpId()),
                    command.eqpId()
            );

            final JsonNode uiMessage = buildManagementUiMessage(command.interfaceType(), command);
            final DeployTargetPlanDraft planDraft = planner.plan(
                    UiCommandEventType.EQP_CREATE,
                    command.eqpId(),
                    command.interfaceType().name(),
                    uiMessage
            );
            final EqpDirectScenarioContext context = localMutationCoordinator.prepareCreate(
                    newExecutionId(),
                    uiMessage,
                    planDraft,
                    command
            );
            runner.run(context);
            return EqpCommandResult.ok();
        } catch (Exception exception) {
            return toCommandResult(exception);
        }
    }

    public EqpCommandResult executeUpdate(
            final String eqpId,
            final EqpManagementCommand.Update command
    ) {
        try {
            validator.validateEqpId(eqpId);
            final EqpManagementSnapshot existingSnapshot = requireExistingSnapshot(eqpId);
            validator.validateUpdateCommand(command, existingSnapshot);
            // routePartition 변경은 Gateway listener bind / outbound 연결의 라우팅 책임을 옮기는 행위이므로
            // EQP가 운영 중(STARTED)인 상태에서 즉시 변경을 허용하면 진행 중 메시지가 잘못된 partition으로 라우팅되거나
            // 새 owner Gateway 인스턴스가 listener를 미리 bind하지 못하는 race condition이 발생할 수 있습니다.
            // 정합성을 위해 STARTED 상태에서는 routePartition 변경을 명시적으로 거부합니다.
            // (PASSIVE listener-group routePartition 일관성은 JpaEqpCrudPort의 별도 가드가 함께 보호합니다.)
            rejectRoutePartitionChangeOnStartedEquipment(existingSnapshot, command);

            final JsonNode uiMessage = buildManagementUiMessage(existingSnapshot.eqp().commInterface(), command);
            // Gateway pod 가 owned-transition 분기 (wasOwned/isOwned) 를 판정하려면 옛 routePartition 을 알아야 하므로
            // 모든 EQP_UPDATE 메시지의 gatewayProfile 에 oldRoutePartition 필드를 주입합니다.
            // 변경 없는 케이스에는 OLD == NEW 동일 값으로 들어갑니다.
            injectOldRoutePartition(uiMessage, existingSnapshot.eqp().routePartition());
            // routePartition 이 변경되면 옛 owner pod 의 listener 해제 + 새 owner pod 의 listener bind 가
            // sequential 하게 필요하므로 전용 시나리오(EQP_UPDATE_PARTITION_MOVE) 로 분기합니다.
            // 그 외에는 기존 EQP_UPDATE_GENERIC 시나리오를 사용합니다 (단일 publish 흐름).
            final String resolvedScenarioCode = resolveUpdateScenarioCode(existingSnapshot, command);
            final DeployTargetPlanDraft planDraft = planner.plan(
                    UiCommandEventType.EQP_UPDATE,
                    eqpId,
                    existingSnapshot.eqp().commInterface().name(),
                    uiMessage,
                    resolvedScenarioCode
            );
            final EqpDirectScenarioContext context = localMutationCoordinator.prepareUpdate(
                    newExecutionId(),
                    eqpId,
                    command.actor(),
                    uiMessage,
                    planDraft,
                    existingSnapshot,
                    command
            );
            runner.run(context);
            return EqpCommandResult.ok();
        } catch (Exception exception) {
            return toCommandResult(exception);
        }
    }

    /**
     * EQP_UPDATE 의 routePartition 변경 여부에 따라 시나리오 코드를 선택합니다.
     *
     * <p>옛/새 routePartition 이 모두 존재하고 서로 다르면 {@code EQP_UPDATE_PARTITION_MOVE} 를 반환하고,
     * 그 외 (변경 없음 / 어느 쪽이 null) 에는 {@code EQP_UPDATE_GENERIC} 를 반환합니다.</p>
     */
    private String resolveUpdateScenarioCode(
            final EqpManagementSnapshot existingSnapshot,
            final EqpManagementCommand.Update command
    ) {
        final Integer oldRoutePartition = existingSnapshot.eqp().routePartition();
        final Integer newRoutePartition = command.routePartition();
        if (oldRoutePartition != null
                && newRoutePartition != null
                && !oldRoutePartition.equals(newRoutePartition)) {
            return "EQP_UPDATE_PARTITION_MOVE";
        }
        return "EQP_UPDATE_GENERIC";
    }

    /**
     * uiMessage 의 {@code gatewayProfile} 노드에 {@code oldRoutePartition} 필드를 주입합니다.
     * Gateway pod 측 EQP_UPDATE 핸들러가 wasOwned/isOwned 분기로 PASSIVE listener 처리를 결정하는 데 사용합니다.
     */
    private void injectOldRoutePartition(final JsonNode uiMessage, final Integer oldRoutePartition) {
        if (oldRoutePartition == null) {
            return;
        }
        if (!(uiMessage instanceof tools.jackson.databind.node.ObjectNode root)) {
            return;
        }
        final JsonNode gatewayProfileNode = root.get("gatewayProfile");
        if (gatewayProfileNode instanceof tools.jackson.databind.node.ObjectNode gatewayProfile) {
            gatewayProfile.put("oldRoutePartition", oldRoutePartition.intValue());
        }
    }

    public EqpCommandResult executeDelete(final String eqpId) {
        try {
            validator.validateEqpId(eqpId);
            final EqpManagementSnapshot existingSnapshot = requireExistingSnapshot(eqpId);
            // 활성 demo job이 있으면 tc_demo_job FK 위반으로 finalize 단계에서 실패하므로,
            // Gateway/Business 정리에 들어가기 전에 미리 거부합니다.
            rejectIfActiveDemoJobExists(existingSnapshot);
            final String interfaceType = existingSnapshot.eqp().commInterface().name();
            final JsonNode uiMessage = deleteUiMessage();
            // PASSIVE 설비는 EQP_END lifecycle을 지원하지 않으므로 END_EQP_IF_RUNNING 단계가 빠진 전용 시나리오를 사용합니다.
            final String resolvedScenarioCode = resolveDeleteScenarioCode(existingSnapshot);
            final DeployTargetPlanDraft planDraft = planner.plan(
                    UiCommandEventType.EQP_DELETE,
                    eqpId,
                    interfaceType,
                    uiMessage,
                    resolvedScenarioCode
            );
            final EqpDirectScenarioContext context = localMutationCoordinator.prepareExistingStateEvent(
                    newExecutionId(),
                    UiCommandEventType.EQP_DELETE,
                    eqpId,
                    interfaceType,
                    "SYSTEM",
                    uiMessage,
                    planDraft,
                    existingSnapshot
            );
            runner.run(context);
            return EqpCommandResult.ok();
        } catch (Exception exception) {
            return toCommandResult(exception);
        }
    }

    private String resolveDeleteScenarioCode(final EqpManagementSnapshot snapshot) {
        final String commMode = snapshot.eqp().commMode();
        if ("PASSIVE".equalsIgnoreCase(commMode == null ? "" : commMode.trim())) {
            return "EQP_DELETE_SAFE_PASSIVE";
        }
        return "EQP_DELETE_SAFE";
    }

    private void rejectIfActiveDemoJobExists(final EqpManagementSnapshot snapshot) {
        final List<String> activeJobIds = demoJobCleanupPort.findActiveDemoJobIds(snapshot.eqp().eqpKey());
        if (!activeJobIds.isEmpty()) {
            throw new UiConflictException(
                    "해당 설비에서 실행 중인 demo job이 있어 삭제할 수 없습니다. demoJobIds=" + activeJobIds
            );
        }
    }

    public EqpCommandResult executeStart(
            final String eqpId,
            final String requestedInterfaceType,
            final JsonNode uiMessage,
            final String actor
    ) {
        return executeExistingStateEvent(UiCommandEventType.EQP_START, eqpId, requestedInterfaceType, actor, ensureObject(uiMessage));
    }

    public EqpCommandResult executeEnd(
            final String eqpId,
            final String requestedInterfaceType,
            final JsonNode uiMessage,
            final String actor
    ) {
        return executeExistingStateEvent(UiCommandEventType.EQP_END, eqpId, requestedInterfaceType, actor, ensureObject(uiMessage));
    }

    public EqpCommandResult executeSendMessage(
            final String eqpId,
            final String requestedInterfaceType,
            final JsonNode uiMessage,
            final String actor
    ) {
        return executeExistingStateEvent(UiCommandEventType.EQP_SEND_MESSAGE, eqpId, requestedInterfaceType, actor, ensureObject(uiMessage));
    }

    public EqpCommandResult executeReconnect(
            final String eqpId,
            final String requestedInterfaceType,
            final JsonNode uiMessage,
            final String actor
    ) {
        return executeExistingStateEvent(UiCommandEventType.EQP_RECONNECT, eqpId, requestedInterfaceType, actor, ensureObject(uiMessage));
    }

    public EqpCommandResult executeGatewayJarUpdate(
            final String eqpId,
            final Long gatewayJarRef,
            final String actor
    ) {
        final ObjectNode root = objectMapper.createObjectNode();
        root.putObject("gatewayJar").put("jarRef", gatewayJarRef);
        return executeExistingStateEvent(UiCommandEventType.EQP_GATEWAY_JARFILE_UPDATE, eqpId, null, actor, root);
    }

    public EqpCommandResult executeBusinessJarUpdate(
            final String eqpId,
            final Long businessJarRef,
            final String actor
    ) {
        try {
            final EqpManagementSnapshot existingSnapshot = requireExistingSnapshot(eqpId);
            final ObjectNode root = objectMapper.createObjectNode();
            root.putObject("modelInfo").put("modelVersionKey", existingSnapshot.eqp().modelVersionKey());
            root.putObject("businessJar").put("jarRef", businessJarRef);
            return executeExistingStateEvent(
                    UiCommandEventType.EQP_BUSINESS_JARFILE_UPDATE,
                    eqpId,
                    existingSnapshot.eqp().commInterface().name(),
                    actor,
                    root
            );
        } catch (Exception exception) {
            return toCommandResult(exception);
        }
    }

    private EqpCommandResult executeExistingStateEvent(
            final UiCommandEventType eventType,
            final String eqpId,
            final String requestedInterfaceType,
            final String actor,
            final JsonNode uiMessage
    ) {
        try {
            validator.validateEqpId(eqpId);
            final EqpManagementSnapshot existingSnapshot = requireExistingSnapshot(eqpId);
            rejectPassiveLifecycleEvent(eventType, existingSnapshot);
            final String interfaceType = resolveInterfaceType(requestedInterfaceType, existingSnapshot);
            final DeployTargetPlanDraft planDraft = planner.plan(eventType, eqpId, interfaceType, uiMessage);
            final EqpDirectScenarioContext context = localMutationCoordinator.prepareExistingStateEvent(
                    newExecutionId(),
                    eventType,
                    eqpId,
                    interfaceType,
                    actor,
                    uiMessage,
                    planDraft,
                    existingSnapshot
            );
            runner.run(context);
            return EqpCommandResult.ok();
        } catch (Exception exception) {
            return toCommandResult(exception);
        }
    }

    private EqpManagementSnapshot requireExistingSnapshot(final String eqpId) {
        return validator.requireExistingSnapshot(persistenceService.findSnapshotByEqpId(eqpId), eqpId);
    }

    private JsonNode buildManagementUiMessage(
            final UiProtocolType interfaceType,
            final EqpManagementCommand.Create command
    ) {
        return buildManagementUiMessage(
                interfaceType,
                command.commMode(),
                command.eqpIp(),
                command.eqpPort(),
                command.modelVersionKey(),
                command.gatewayJarRef(),
                command.businessJarRef(),
                command.routePartition(),
                command.hsmsSettings(),
                command.socketSettings()
        );
    }

    private JsonNode buildManagementUiMessage(
            final UiProtocolType interfaceType,
            final EqpManagementCommand.Update command
    ) {
        final ObjectNode root = objectMapper.createObjectNode();
        root.put("interfaceType", interfaceType.name());

        final ObjectNode gatewayProfile = root.putObject("gatewayProfile");
        gatewayProfile.put("connectionMode", command.commMode());
        gatewayProfile.put("eqpIp", command.eqpIp());
        gatewayProfile.put("eqpPort", command.eqpPort());
        // routePartition은 deploy planner가 delta를 계산하는 데 사용되며, 변경되면 Business runtime cache 갱신 step이 트리거됩니다.
        if (command.routePartition() != null) {
            gatewayProfile.put("routePartition", command.routePartition());
        }

        if (command.socketSettings() != null) {
            gatewayProfile.put("socketType", command.socketSettings().socketProtocolType());
            final ObjectNode socketSettingsNode = gatewayProfile.putObject("socketSettings");
            if (command.socketSettings().socketProtocolType() != null) {
                socketSettingsNode.put("socketProtocolType", command.socketSettings().socketProtocolType());
            }
            if (command.socketSettings().charset() != null) {
                socketSettingsNode.put("charset", command.socketSettings().charset());
            }
            if (command.socketSettings().heartbeatEnabled() != null) {
                socketSettingsNode.put("heartbeatEnabled", command.socketSettings().heartbeatEnabled());
            }
            if (command.socketSettings().heartbeatInterval() != null) {
                socketSettingsNode.put("heartbeatInterval", command.socketSettings().heartbeatInterval());
            }
            if (command.socketSettings().readTimeout() != null) {
                socketSettingsNode.put("readTimeout", command.socketSettings().readTimeout());
            }
            if (command.socketSettings().writeTimeout() != null) {
                socketSettingsNode.put("writeTimeout", command.socketSettings().writeTimeout());
            }
            if (command.socketSettings().maxFrameSizeBytes() != null) {
                socketSettingsNode.put("maxFrameSizeBytes", command.socketSettings().maxFrameSizeBytes());
            }
            if (command.socketSettings().keepAliveEnabled() != null) {
                socketSettingsNode.put("keepAliveEnabled", command.socketSettings().keepAliveEnabled());
            }
        }

        if (command.hsmsSettings() != null) {
            if (command.hsmsSettings().deviceId() != null) {
                gatewayProfile.put("hsmsDeviceId", command.hsmsSettings().deviceId());
            }
            final ObjectNode hsmsSettingsNode = gatewayProfile.putObject("hsmsSettings");
            if (command.hsmsSettings().deviceId() != null) {
                hsmsSettingsNode.put("deviceId", command.hsmsSettings().deviceId());
            }
            if (command.hsmsSettings().t3Timeout() != null) {
                hsmsSettingsNode.put("t3Timeout", command.hsmsSettings().t3Timeout());
            }
            if (command.hsmsSettings().t5Timeout() != null) {
                hsmsSettingsNode.put("t5Timeout", command.hsmsSettings().t5Timeout());
            }
            if (command.hsmsSettings().t6Timeout() != null) {
                hsmsSettingsNode.put("t6Timeout", command.hsmsSettings().t6Timeout());
            }
            if (command.hsmsSettings().t7Timeout() != null) {
                hsmsSettingsNode.put("t7Timeout", command.hsmsSettings().t7Timeout());
            }
            if (command.hsmsSettings().t8Timeout() != null) {
                hsmsSettingsNode.put("t8Timeout", command.hsmsSettings().t8Timeout());
            }
            if (command.hsmsSettings().linkTestEnabled() != null) {
                hsmsSettingsNode.put("linkTestEnabled", command.hsmsSettings().linkTestEnabled());
            }
            if (command.hsmsSettings().linkTestInterval() != null) {
                hsmsSettingsNode.put("linkTestInterval", command.hsmsSettings().linkTestInterval());
            }
            if (command.hsmsSettings().maxMsgBytes() != null) {
                hsmsSettingsNode.put("maxMsgBytes", command.hsmsSettings().maxMsgBytes());
            }
        }

        return root;
    }

    private JsonNode buildManagementUiMessage(
            final UiProtocolType interfaceType,
            final String commMode,
            final String eqpIp,
            final Integer eqpPort,
            final long modelVersionKey,
            final Long gatewayJarRef,
            final Long businessJarRef,
            final Integer routePartition,
            final EqpManagementCommand.HsmsSettings hsmsSettings,
            final EqpManagementCommand.SocketSettings socketSettings
    ) {
        final ObjectNode root = objectMapper.createObjectNode();
        root.put("interfaceType", interfaceType.name());
        root.putObject("modelInfo").put("modelVersionKey", modelVersionKey);
        root.set("modelParams", modelParamPayloadSupport.ensureModelParamsPayload(null, modelVersionKey));

        final ObjectNode gatewayProfile = root.putObject("gatewayProfile");
        gatewayProfile.put("connectionMode", commMode);
        gatewayProfile.put("eqpIp", eqpIp);
        gatewayProfile.put("eqpPort", eqpPort);
        if (routePartition != null) {
            gatewayProfile.put("routePartition", routePartition);
        }

        if (socketSettings != null) {
            gatewayProfile.put("socketType", socketSettings.socketProtocolType());
            final ObjectNode socketSettingsNode = gatewayProfile.putObject("socketSettings");
            if (socketSettings.socketProtocolType() != null) {
                socketSettingsNode.put("socketProtocolType", socketSettings.socketProtocolType());
            }
            if (socketSettings.charset() != null) {
                socketSettingsNode.put("charset", socketSettings.charset());
            }
            if (socketSettings.heartbeatEnabled() != null) {
                socketSettingsNode.put("heartbeatEnabled", socketSettings.heartbeatEnabled());
            }
            if (socketSettings.heartbeatInterval() != null) {
                socketSettingsNode.put("heartbeatInterval", socketSettings.heartbeatInterval());
            }
            if (socketSettings.readTimeout() != null) {
                socketSettingsNode.put("readTimeout", socketSettings.readTimeout());
            }
            if (socketSettings.writeTimeout() != null) {
                socketSettingsNode.put("writeTimeout", socketSettings.writeTimeout());
            }
            if (socketSettings.maxFrameSizeBytes() != null) {
                socketSettingsNode.put("maxFrameSizeBytes", socketSettings.maxFrameSizeBytes());
            }
            if (socketSettings.keepAliveEnabled() != null) {
                socketSettingsNode.put("keepAliveEnabled", socketSettings.keepAliveEnabled());
            }
        }

        if (hsmsSettings != null) {
            if (hsmsSettings.deviceId() != null) {
                gatewayProfile.put("hsmsDeviceId", hsmsSettings.deviceId());
            }
            final ObjectNode hsmsSettingsNode = gatewayProfile.putObject("hsmsSettings");
            if (hsmsSettings.deviceId() != null) {
                hsmsSettingsNode.put("deviceId", hsmsSettings.deviceId());
            }
            if (hsmsSettings.t3Timeout() != null) {
                hsmsSettingsNode.put("t3Timeout", hsmsSettings.t3Timeout());
            }
            if (hsmsSettings.t5Timeout() != null) {
                hsmsSettingsNode.put("t5Timeout", hsmsSettings.t5Timeout());
            }
            if (hsmsSettings.t6Timeout() != null) {
                hsmsSettingsNode.put("t6Timeout", hsmsSettings.t6Timeout());
            }
            if (hsmsSettings.t7Timeout() != null) {
                hsmsSettingsNode.put("t7Timeout", hsmsSettings.t7Timeout());
            }
            if (hsmsSettings.t8Timeout() != null) {
                hsmsSettingsNode.put("t8Timeout", hsmsSettings.t8Timeout());
            }
            if (hsmsSettings.linkTestEnabled() != null) {
                hsmsSettingsNode.put("linkTestEnabled", hsmsSettings.linkTestEnabled());
            }
            if (hsmsSettings.linkTestInterval() != null) {
                hsmsSettingsNode.put("linkTestInterval", hsmsSettings.linkTestInterval());
            }
            if (hsmsSettings.maxMsgBytes() != null) {
                hsmsSettingsNode.put("maxMsgBytes", hsmsSettings.maxMsgBytes());
            }
        }

        if (gatewayJarRef != null) {
            root.putObject("gatewayJar").put("jarRef", gatewayJarRef);
        }
        if (businessJarRef != null) {
            root.putObject("businessJar").put("jarRef", businessJarRef);
        }

        return root;
    }

    private JsonNode deleteUiMessage() {
        final ObjectNode root = objectMapper.createObjectNode();
        root.put("deleteMode", "SAFE");
        return root;
    }

    private JsonNode ensureObject(final JsonNode uiMessage) {
        if (uiMessage == null || uiMessage.isNull()) {
            return objectMapper.createObjectNode();
        }
        if (!uiMessage.isObject()) {
            throw new UiBadRequestException("uiMessage는 JSON object 여야 합니다.");
        }
        return uiMessage;
    }

    private String resolveInterfaceType(
            final String requestedInterfaceType,
            final EqpManagementSnapshot snapshot
    ) {
        if (requestedInterfaceType != null && !requestedInterfaceType.isBlank()) {
            return requestedInterfaceType.trim();
        }
        return snapshot.eqp().commInterface().name();
    }

    private void rejectPassiveLifecycleEvent(
            final UiCommandEventType eventType,
            final EqpManagementSnapshot snapshot
    ) {
        if (!isLifecycleEvent(eventType)) {
            return;
        }
        final String commMode = snapshot.eqp().commMode();
        if ("PASSIVE".equalsIgnoreCase(commMode == null ? "" : commMode.trim())) {
            throw new UiBadRequestException("PASSIVE 설비는 Start/End/Restart 명령 대상이 아닙니다.");
        }
    }

    private boolean isLifecycleEvent(final UiCommandEventType eventType) {
        return eventType == UiCommandEventType.EQP_START
                || eventType == UiCommandEventType.EQP_END
                || eventType == UiCommandEventType.EQP_RECONNECT;
    }

    /**
     * EQP가 STARTED 상태(controlState == REMOTE 또는 LOCAL)이면서 routePartition이 변경되는 경우 거부합니다.
     *
     * <p>routePartition 변경은 Gateway equipment context와 Business runtime cache 양쪽을 갱신하는 시나리오 step으로
     * 처리되지만, EQP가 운영 중인 상태에서는 이미 진행 중인 메시지/연결이 잘못된 partition으로 흘러갈 위험이 있습니다.
     * 따라서 routePartition 변경은 EQP가 OFFLINE/DOWN 상태일 때만 허용하고, 운영 중인 경우 운영자가 명시적으로
     * EQP를 END한 뒤 변경하고 다시 START 하도록 강제합니다.</p>
     */
    private void rejectRoutePartitionChangeOnStartedEquipment(
            final EqpManagementSnapshot existingSnapshot,
            final EqpManagementCommand.Update command
    ) {
        final Integer newRoutePartition = command.routePartition();
        if (newRoutePartition == null) {
            return;
        }
        if (Objects.equals(newRoutePartition, existingSnapshot.eqp().routePartition())) {
            return;
        }
        final var runtimeState = existingSnapshot.runtimeState();
        if (runtimeState == null || runtimeState.controlState() == null) {
            return;
        }
        final String controlStateName = runtimeState.controlState().name();
        if ("REMOTE".equals(controlStateName) || "LOCAL".equals(controlStateName)) {
            throw new UiConflictException(
                    "EQP가 운영 중(controlState=" + controlStateName + ")인 상태에서는 routePartition을 변경할 수 없습니다. "
                            + "먼저 EQP를 종료(End)한 뒤 routePartition을 변경하고 다시 시작해 주세요."
            );
        }
    }

    private String newExecutionId() {
        return TraceIdGenerator.newTraceId();
    }

    private EqpCommandResult toCommandResult(final Exception exception) {
        if (exception instanceof UiBadRequestException uiBadRequestException) {
            return EqpCommandResult.error(BAD_REQUEST_STATUS, "INVALID_REQUEST", uiBadRequestException.getMessage());
        }
        if (exception instanceof UiNotFoundException uiNotFoundException) {
            return EqpCommandResult.error(NOT_FOUND_STATUS, "NOT_FOUND", uiNotFoundException.getMessage());
        }
        if (exception instanceof UiConflictException uiConflictException) {
            return EqpCommandResult.error(CONFLICT_STATUS, "CONFLICT", uiConflictException.getMessage());
        }
        if (exception instanceof EqpManagementCommandFailureException commandFailureException) {
            return EqpCommandResult.error(
                    commandFailureException.statusCode(),
                    commandFailureException.errorCode(),
                    commandFailureException.getMessage()
            );
        }

        log.error("direct EQP scenario 실행 중 예기치 못한 오류가 발생했습니다.", exception);
        return EqpCommandResult.error(INTERNAL_ERROR_STATUS, "INTERNAL_ERROR", "요청 처리 중 내부 오류가 발생했습니다.");
    }
}
