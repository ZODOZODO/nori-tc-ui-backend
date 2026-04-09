package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.nori.tc.ui.core.deploy.DeployTargetPlanDraft;
import com.nori.tc.ui.core.eqp.EqpCommandResult;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.port.db.ModelDetailQueryPort;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

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
    private final ObjectMapper objectMapper;
    private final ModelParamPayloadSupport modelParamPayloadSupport;

    public EqpDirectScenarioService(
            final EqpPersistenceService persistenceService,
            final EqpManagementCommandValidator validator,
            final EqpDirectScenarioPlanner planner,
            final EqpDirectScenarioLocalMutationCoordinator localMutationCoordinator,
            final EqpDirectScenarioRunner runner,
            final ModelDetailQueryPort modelDetailQueryPort,
            final ObjectMapper objectMapper
    ) {
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService is null");
        this.validator = Objects.requireNonNull(validator, "validator is null");
        this.planner = Objects.requireNonNull(planner, "planner is null");
        this.localMutationCoordinator = Objects.requireNonNull(localMutationCoordinator, "localMutationCoordinator is null");
        this.runner = Objects.requireNonNull(runner, "runner is null");
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

            final JsonNode uiMessage = buildManagementUiMessage(existingSnapshot.eqp().commInterface(), command);
            final DeployTargetPlanDraft planDraft = planner.plan(
                    UiCommandEventType.EQP_UPDATE,
                    eqpId,
                    existingSnapshot.eqp().commInterface().name(),
                    uiMessage
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

    public EqpCommandResult executeDelete(final String eqpId) {
        try {
            validator.validateEqpId(eqpId);
            final EqpManagementSnapshot existingSnapshot = requireExistingSnapshot(eqpId);
            final String interfaceType = existingSnapshot.eqp().commInterface().name();
            final JsonNode uiMessage = deleteUiMessage();
            final DeployTargetPlanDraft planDraft = planner.plan(
                    UiCommandEventType.EQP_DELETE,
                    eqpId,
                    interfaceType,
                    uiMessage
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

    private String newExecutionId() {
        return UUID.randomUUID().toString();
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
