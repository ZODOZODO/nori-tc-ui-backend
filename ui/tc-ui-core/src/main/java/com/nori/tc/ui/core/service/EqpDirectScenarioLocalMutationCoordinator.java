package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nori.tc.ui.core.deploy.DeployDesiredState;
import com.nori.tc.ui.core.deploy.DeployTargetPlanDraft;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * direct EQP event의 local 선반영, finalization, local rollback을 담당합니다.
 */
@Service
public class EqpDirectScenarioLocalMutationCoordinator {

    private final EqpPersistenceService persistenceService;
    private final DeployServiceSnapshotSupport snapshotSupport;

    public EqpDirectScenarioLocalMutationCoordinator(
            final EqpPersistenceService persistenceService,
            final EqpCrudPort eqpCrudPort,
            final ObjectMapper objectMapper
    ) {
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService is null");
        this.snapshotSupport = new DeployServiceSnapshotSupport(
                Objects.requireNonNull(eqpCrudPort, "eqpCrudPort is null"),
                Objects.requireNonNull(objectMapper, "objectMapper is null")
        );
    }

    public EqpDirectScenarioContext prepareCreate(
            final String executionId,
            final JsonNode uiMessage,
            final DeployTargetPlanDraft planDraft,
            final EqpManagementCommand.Create command
    ) {
        final PersistedDeployTarget syntheticTarget = syntheticTarget(planDraft);
        final JsonNode targetPreState = snapshotSupport.buildTargetPreStateSnapshot(syntheticTarget);
        final EqpManagementSnapshot createdSnapshot = persistenceService.create(command);
        return new EqpDirectScenarioContext(
                executionId,
                UiCommandEventType.EQP_CREATE,
                command.eqpId(),
                command.interfaceType().name(),
                normalizeActor(command.actor()),
                uiMessage,
                planDraft,
                syntheticTarget,
                null,
                createdSnapshot,
                targetPreState
        );
    }

    public EqpDirectScenarioContext prepareUpdate(
            final String executionId,
            final String eqpId,
            final String actor,
            final JsonNode uiMessage,
            final DeployTargetPlanDraft planDraft,
            final EqpManagementSnapshot existingSnapshot,
            final EqpManagementCommand.Update command
    ) {
        final PersistedDeployTarget syntheticTarget = syntheticTarget(planDraft);
        final JsonNode targetPreState = snapshotSupport.buildTargetPreStateSnapshot(syntheticTarget);
        final EqpManagementSnapshot updatedSnapshot = persistenceService.update(eqpId, command);
        return new EqpDirectScenarioContext(
                executionId,
                UiCommandEventType.EQP_UPDATE,
                eqpId,
                existingSnapshot.eqp().commInterface().name(),
                normalizeActor(actor),
                uiMessage,
                planDraft,
                syntheticTarget,
                existingSnapshot,
                updatedSnapshot,
                targetPreState
        );
    }

    public EqpDirectScenarioContext prepareExistingStateEvent(
            final String executionId,
            final UiCommandEventType eventType,
            final String eqpId,
            final String interfaceType,
            final String actor,
            final JsonNode uiMessage,
            final DeployTargetPlanDraft planDraft,
            final EqpManagementSnapshot existingSnapshot
    ) {
        final PersistedDeployTarget syntheticTarget = syntheticTarget(planDraft);
        final JsonNode targetPreState = snapshotSupport.buildTargetPreStateSnapshot(syntheticTarget);
        return new EqpDirectScenarioContext(
                executionId,
                eventType,
                eqpId,
                interfaceType,
                normalizeActor(actor),
                uiMessage,
                planDraft,
                syntheticTarget,
                existingSnapshot,
                existingSnapshot,
                targetPreState
        );
    }

    public void finalizeSuccess(final EqpDirectScenarioContext context) {
        switch (context.eventType()) {
            case EQP_DELETE -> persistenceService.delete(context.eqpId());
            case EQP_PARAMETER_UPDATE -> persistenceService.update(
                    context.eqpId(),
                    buildFinalizationUpdateCommand(
                            context.executionSnapshot(),
                            context.actor(),
                            context.planDraft().desiredState().eqpParamVersion(),
                            null,
                            null
                    )
            );
            case EQP_GATEWAY_JARFILE_UPDATE -> persistenceService.update(
                    context.eqpId(),
                    buildFinalizationUpdateCommand(
                            context.executionSnapshot(),
                            context.actor(),
                            null,
                            context.planDraft().desiredState().gatewayJarRef(),
                            null
                    )
            );
            case EQP_BUSINESS_JARFILE_UPDATE -> persistenceService.update(
                    context.eqpId(),
                    buildFinalizationUpdateCommand(
                            context.executionSnapshot(),
                            context.actor(),
                            null,
                            null,
                            context.planDraft().desiredState().businessJarRef()
                    )
            );
            case EQP_CREATE,
                 EQP_UPDATE,
                 EQP_START,
                 EQP_END,
                 EQP_SEND_MESSAGE,
                 EQP_RECONNECT -> {
                // finalization 없음
            }
            default -> {
                // direct service가 다루지 않는 event 없음
            }
        }
    }

    public void compensateLocalFailure(final EqpDirectScenarioContext context) {
        switch (context.eventType()) {
            case EQP_CREATE -> persistenceService.delete(context.eqpId());
            case EQP_UPDATE,
                 EQP_DELETE,
                 EQP_PARAMETER_UPDATE,
                 EQP_GATEWAY_JARFILE_UPDATE,
                 EQP_BUSINESS_JARFILE_UPDATE -> {
                if (context.previousSnapshot() != null) {
                    persistenceService.restore(context.previousSnapshot());
                }
            }
            case EQP_START,
                 EQP_END,
                 EQP_SEND_MESSAGE,
                 EQP_RECONNECT -> {
                // local rollback 없음
            }
            default -> {
                // no-op
            }
        }
    }

    private EqpManagementCommand.Update buildFinalizationUpdateCommand(
            final EqpManagementSnapshot snapshot,
            final String actor,
            final String appliedParamVersion,
            final Long gatewayJarRef,
            final Long businessJarRef
    ) {
        final UiProtocolType interfaceType = snapshot.eqp().commInterface();
        return new EqpManagementCommand.Update(
                normalizeActor(actor),
                snapshot.eqp().commMode(),
                snapshot.eqp().isDev(),
                snapshot.eqp().routePartition(),
                snapshot.eqp().eqpIp(),
                snapshot.eqp().eqpPort(),
                snapshot.eqp().modelVersionKey(),
                appliedParamVersion != null ? appliedParamVersion : snapshot.eqp().appliedParamVersion(),
                gatewayJarRef != null ? gatewayJarRef : snapshot.eqp().gatewayJarKey(),
                businessJarRef != null ? businessJarRef : snapshot.eqp().businessJarKey(),
                snapshot.logPolicy() == null ? null : new EqpManagementCommand.LogSettings(
                        snapshot.logPolicy().logLevel(),
                        snapshot.logPolicy().logRetentionDays(),
                        snapshot.logPolicy().logPath()
                ),
                interfaceType == UiProtocolType.SECS && snapshot.hsms() != null
                        ? new EqpManagementCommand.HsmsSettings(
                        snapshot.hsms().deviceId(),
                        snapshot.hsms().t3Timeout(),
                        snapshot.hsms().t5Timeout(),
                        snapshot.hsms().t6Timeout(),
                        snapshot.hsms().t7Timeout(),
                        snapshot.hsms().t8Timeout(),
                        snapshot.hsms().linkTestEnabled(),
                        snapshot.hsms().linkTestInterval(),
                        snapshot.hsms().maxMsgBytes()
                )
                        : null,
                interfaceType == UiProtocolType.SOCKET && snapshot.socket() != null
                        ? new EqpManagementCommand.SocketSettings(
                        snapshot.socket().socketProtocolType(),
                        snapshot.socket().charset(),
                        snapshot.socket().heartbeatEnabled(),
                        snapshot.socket().heartbeatInterval(),
                        snapshot.socket().readTimeout(),
                        snapshot.socket().writeTimeout(),
                        snapshot.socket().maxFrameSizeBytes(),
                        snapshot.socket().keepAliveEnabled()
                )
                        : null
        );
    }

    private PersistedDeployTarget syntheticTarget(final DeployTargetPlanDraft planDraft) {
        return new PersistedDeployTarget(0L, 1, planDraft);
    }

    private String normalizeActor(final String actor) {
        if (actor == null || actor.isBlank()) {
            return "SYSTEM";
        }
        return actor.trim();
    }
}
