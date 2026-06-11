package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nori.tc.ui.core.deploy.DeployTargetPlanDraft;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import com.nori.tc.ui.core.port.db.ModelDetailQueryPort;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpHsms;
import com.nori.tc.ui.domain.eqp.UiEqpLogPolicy;
import com.nori.tc.ui.domain.eqp.UiEqpPlc;
import com.nori.tc.ui.domain.eqp.UiEqpSocket;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
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
            final ModelDetailQueryPort modelDetailQueryPort,
            final ObjectMapper objectMapper
    ) {
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService is null");
        this.snapshotSupport = new DeployServiceSnapshotSupport(
                Objects.requireNonNull(eqpCrudPort, "eqpCrudPort is null"),
                Objects.requireNonNull(modelDetailQueryPort, "modelDetailQueryPort is null"),
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
                targetPreState,
                null
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
        // publish-first 패턴: DB는 변경하지 않고 명령을 보관해 finalizeSuccess 에서 1회만 update.
        // step publish 가 사용할 executionSnapshot 은 existingSnapshot 위에 command 를 덮어 합성합니다.
        final EqpManagementSnapshot synthesizedUpdatedSnapshot =
                synthesizeUpdatedSnapshotInMemory(existingSnapshot, command, actor);
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
                synthesizedUpdatedSnapshot,
                targetPreState,
                command
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
                targetPreState,
                null
        );
    }

    public void finalizeSuccess(final EqpDirectScenarioContext context) {
        switch (context.eventType()) {
            case EQP_DELETE -> persistenceService.delete(context.eqpId());
            case EQP_UPDATE -> {
                // publish-first 패턴: 모든 step PASS 후 1회 DB update.
                // pendingUpdateCommand 는 prepareUpdate 에서 보관됐습니다.
                if (context.pendingUpdateCommand() != null) {
                    persistenceService.update(context.eqpId(), context.pendingUpdateCommand());
                }
            }
            case EQP_GATEWAY_JARFILE_UPDATE -> persistenceService.update(
                    context.eqpId(),
                    buildFinalizationUpdateCommand(
                        context.executionSnapshot(),
                        context.actor(),
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
                        context.planDraft().desiredState().businessJarRef()
                    )
            );
            case EQP_CREATE,
                 EQP_MODEL_UPDATE,
                 EQP_START,
                 EQP_END,
                 EQP_SEND_MESSAGE,
                 EQP_RECONNECT,
                 // 그룹 명령은 enabled_poll_groups 변이를 EqpDirectScenarioService 가 runner PASS 직후 직접 수행하므로
                 // finalizeSuccess 단계의 별도 처리가 없다.
                 EQP_PLC_GROUP_START,
                 EQP_PLC_GROUP_END -> {
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
            case EQP_UPDATE -> {
                // publish-first 패턴: DB 가 아직 변경되지 않았으므로 보상 불필요.
            }
            case EQP_MODEL_UPDATE,
                 EQP_DELETE,
                 EQP_GATEWAY_JARFILE_UPDATE,
                 EQP_BUSINESS_JARFILE_UPDATE -> {
                if (context.previousSnapshot() != null) {
                    persistenceService.restore(context.previousSnapshot());
                }
            }
            case EQP_START,
                 EQP_END,
                 EQP_SEND_MESSAGE,
                 EQP_RECONNECT,
                 // 그룹 명령은 DB 변이가 runner PASS 직후에만 일어나므로 실패 시 보상할 local 변경이 없다.
                 EQP_PLC_GROUP_START,
                 EQP_PLC_GROUP_END -> {
                // local rollback 없음
            }
            default -> {
                // no-op
            }
        }
    }

    /**
     * EQP_UPDATE 의 publish-first 패턴을 위해 DB write 없이 in-memory 로 post-update snapshot 을 합성합니다.
     *
     * <p>publish step 들이 사용하는 {@code executionSnapshot} 이 변경 후 상태를 반영하도록 existingSnapshot 위에
     * command 를 덮어 씌웁니다. 모델/runtime/jar 같은 외부 조회 필드는 existingSnapshot 그대로 유지합니다
     * (modelVersionKey 변경 케이스는 본 PR 범위 밖, finalizeSuccess 의 실제 DB update 가 이를 반영합니다).</p>
     */
    EqpManagementSnapshot synthesizeUpdatedSnapshotInMemory(
            final EqpManagementSnapshot existing,
            final EqpManagementCommand.Update command,
            final String actor
    ) {
        final UiEqp existingEqp = existing.eqp();
        final OffsetDateTime now = OffsetDateTime.now();
        final UiEqp updatedEqp = new UiEqp(
                existingEqp.eqpKey(),
                existingEqp.eqpId(),
                command.lineKey(),
                existingEqp.commInterface(),
                command.commMode(),
                command.isDev(),
                command.routePartition(),
                command.eqpIp(),
                command.eqpPort() == null ? existingEqp.eqpPort() : command.eqpPort(),
                command.modelVersionKey(),
                command.appliedParamVersion(),
                command.gatewayJarRef() != null ? command.gatewayJarRef() : existingEqp.gatewayJarKey(),
                command.businessJarRef() != null ? command.businessJarRef() : existingEqp.businessJarKey(),
                existingEqp.enabled(),
                existingEqp.createdAt(),
                now,
                existingEqp.createdBy(),
                normalizeActor(actor)
        );

        final UiProtocolType interfaceType = existingEqp.commInterface();
        final UiEqpHsms hsms;
        if (interfaceType == UiProtocolType.SECS && command.hsmsSettings() != null) {
            final EqpManagementCommand.HsmsSettings h = command.hsmsSettings();
            final UiEqpHsms prev = existing.hsms();
            hsms = new UiEqpHsms(
                    existingEqp.eqpKey(),
                    h.deviceId() == null ? (prev == null ? 0 : prev.deviceId()) : h.deviceId(),
                    h.t3Timeout() == null ? (prev == null ? 0 : prev.t3Timeout()) : h.t3Timeout(),
                    h.t5Timeout() == null ? (prev == null ? 0 : prev.t5Timeout()) : h.t5Timeout(),
                    h.t6Timeout() == null ? (prev == null ? 0 : prev.t6Timeout()) : h.t6Timeout(),
                    h.t7Timeout() == null ? (prev == null ? 0 : prev.t7Timeout()) : h.t7Timeout(),
                    h.t8Timeout() == null ? (prev == null ? 0 : prev.t8Timeout()) : h.t8Timeout(),
                    h.linkTestEnabled() == null ? (prev != null && prev.linkTestEnabled()) : h.linkTestEnabled(),
                    h.linkTestInterval() == null ? (prev == null ? 0 : prev.linkTestInterval()) : h.linkTestInterval(),
                    h.maxMsgBytes() == null ? (prev == null ? 0L : prev.maxMsgBytes()) : h.maxMsgBytes(),
                    prev == null ? now : prev.createdAt(),
                    now
            );
        } else {
            hsms = existing.hsms();
        }

        final UiEqpSocket socket;
        if (interfaceType == UiProtocolType.SOCKET && command.socketSettings() != null) {
            final EqpManagementCommand.SocketSettings s = command.socketSettings();
            final UiEqpSocket prev = existing.socket();
            socket = new UiEqpSocket(
                    existingEqp.eqpKey(),
                    s.socketProtocolType() == null ? (prev == null ? null : prev.socketProtocolType()) : s.socketProtocolType(),
                    s.charset() == null ? (prev == null ? null : prev.charset()) : s.charset(),
                    s.heartbeatEnabled() == null ? (prev != null && prev.heartbeatEnabled()) : s.heartbeatEnabled(),
                    s.heartbeatInterval() == null ? (prev == null ? 0 : prev.heartbeatInterval()) : s.heartbeatInterval(),
                    s.readTimeout() == null ? (prev == null ? 0 : prev.readTimeout()) : s.readTimeout(),
                    s.writeTimeout() == null ? (prev == null ? 0 : prev.writeTimeout()) : s.writeTimeout(),
                    s.maxFrameSizeBytes() == null ? (prev == null ? 0 : prev.maxFrameSizeBytes()) : s.maxFrameSizeBytes(),
                    s.keepAliveEnabled() == null ? (prev != null && prev.keepAliveEnabled()) : s.keepAliveEnabled(),
                    prev == null ? now : prev.createdAt(),
                    now
            );
        } else {
            socket = existing.socket();
        }

        final UiEqpPlc plc;
        if (interfaceType == UiProtocolType.PLC && command.plcSettings() != null) {
            final EqpManagementCommand.PlcSettings p = command.plcSettings();
            final UiEqpPlc prev = existing.plc();
            plc = new UiEqpPlc(
                    existingEqp.eqpKey(),
                    p.plcProtocol() == null ? (prev == null ? null : prev.plcProtocol()) : p.plcProtocol(),
                    p.transport() == null ? (prev == null ? null : prev.transport()) : p.transport(),
                    p.unitId() == null ? (prev == null ? 0 : prev.unitId()) : p.unitId(),
                    p.pollTimeoutMs() == null ? (prev == null ? 0 : prev.pollTimeoutMs()) : p.pollTimeoutMs(),
                    p.reconnectMaxRetries() == null ? (prev == null ? 0 : prev.reconnectMaxRetries()) : p.reconnectMaxRetries(),
                    prev == null ? java.util.List.of() : prev.enabledPollGroups(),
                    prev == null ? 0L : prev.version(),
                    prev == null ? now : prev.createdAt(),
                    now
            );
        } else {
            plc = existing.plc();
        }

        final UiEqpLogPolicy logPolicy;
        if (command.logSettings() != null) {
            final EqpManagementCommand.LogSettings l = command.logSettings();
            final UiEqpLogPolicy prev = existing.logPolicy();
            logPolicy = new UiEqpLogPolicy(
                    existingEqp.eqpKey(),
                    l.logLevel() == null ? (prev == null ? null : prev.logLevel()) : l.logLevel(),
                    l.logRetentionDays() == null ? (prev == null ? 0 : prev.logRetentionDays()) : l.logRetentionDays(),
                    l.logPath() == null ? (prev == null ? null : prev.logPath()) : l.logPath(),
                    now
            );
        } else {
            logPolicy = existing.logPolicy();
        }

        return new EqpManagementSnapshot(
                updatedEqp,
                existing.model(),
                hsms,
                socket,
                plc,
                logPolicy,
                existing.runtimeState(),
                existing.connectionState(),
                existing.portStatuses(),
                existing.params(),
                existing.paramVersionMetas(),
                existing.gatewayJar(),
                existing.businessJar()
        );
    }

    private EqpManagementCommand.Update buildFinalizationUpdateCommand(
            final EqpManagementSnapshot snapshot,
            final String actor,
            final Long gatewayJarRef,
            final Long businessJarRef
    ) {
        final UiProtocolType interfaceType = snapshot.eqp().commInterface();
        return new EqpManagementCommand.Update(
                normalizeActor(actor),
                snapshot.eqp().lineKey(),
                snapshot.eqp().commMode(),
                snapshot.eqp().isDev(),
                snapshot.eqp().routePartition(),
                snapshot.eqp().eqpIp(),
                snapshot.eqp().eqpPort(),
                snapshot.eqp().modelVersionKey(),
                snapshot.eqp().appliedParamVersion(),
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
                        : null,
                interfaceType == UiProtocolType.PLC && snapshot.plc() != null
                        ? new EqpManagementCommand.PlcSettings(
                        snapshot.plc().plcProtocol(),
                        snapshot.plc().transport(),
                        snapshot.plc().unitId(),
                        snapshot.plc().pollTimeoutMs(),
                        snapshot.plc().reconnectMaxRetries()
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
