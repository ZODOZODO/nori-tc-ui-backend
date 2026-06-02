package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.deploy.DeployDeltaKind;
import com.nori.tc.ui.core.deploy.DeployDesiredState;
import com.nori.tc.ui.core.deploy.DeployTargetPlanDraft;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * deploy 성공 후 EQP 영속 상태를 확정합니다.
 */
@Service
final class DeployEqpFinalizationService {

    private final EqpPersistenceService persistenceService;

    DeployEqpFinalizationService(final EqpPersistenceService persistenceService) {
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService is null");
    }

    void finalizeTargetSuccess(final PersistedDeployTarget target, final String actor) {
        final DeployTargetPlanDraft planDraft = Objects.requireNonNull(target, "target is null").planDraft();
        if (!requiresEqpFinalization(planDraft)) {
            return;
        }

        final EqpManagementSnapshot snapshot = persistenceService.findSnapshotByEqpId(planDraft.eqpId())
                .orElseThrow(() -> new UiNotFoundException("설비를 찾을 수 없습니다. eqpId=" + planDraft.eqpId()));

        persistenceService.update(
                planDraft.eqpId(),
                buildFinalizationUpdateCommand(snapshot, planDraft.desiredState(), actor)
        );
    }

    private EqpManagementCommand.Update buildFinalizationUpdateCommand(
            final EqpManagementSnapshot snapshot,
            final DeployDesiredState desiredState,
            final String actor
    ) {
        final UiProtocolType interfaceType = snapshot.eqp().commInterface();
        final long resolvedModelVersionKey = desiredState.modelVersionKey() != null && desiredState.modelVersionKey() > 0L
                ? desiredState.modelVersionKey()
                : snapshot.eqp().modelVersionKey();
        final String resolvedEqpParamVersion = desiredState.eqpParamVersion() != null && !desiredState.eqpParamVersion().isBlank()
                ? desiredState.eqpParamVersion().trim()
                : snapshot.eqp().appliedParamVersion();
        final Long resolvedBusinessJarRef = desiredState.businessJarRef() != null && desiredState.businessJarRef() > 0L
                ? desiredState.businessJarRef()
                : snapshot.eqp().businessJarKey();

        return new EqpManagementCommand.Update(
                normalizeActor(actor),
                snapshot.eqp().lineKey(),
                snapshot.eqp().commMode(),
                snapshot.eqp().isDev(),
                snapshot.eqp().routePartition(),
                snapshot.eqp().eqpIp(),
                snapshot.eqp().eqpPort(),
                resolvedModelVersionKey,
                resolvedEqpParamVersion,
                snapshot.eqp().gatewayJarKey(),
                resolvedBusinessJarRef,
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

    private boolean requiresEqpFinalization(final DeployTargetPlanDraft planDraft) {
        return planDraft.deltaKinds().contains(DeployDeltaKind.MODEL_CHANGED)
                || planDraft.deltaKinds().contains(DeployDeltaKind.EQP_PARAM_CHANGED)
                || planDraft.deltaKinds().contains(DeployDeltaKind.BUSINESS_JAR_CHANGED);
    }

    private String normalizeActor(final String actor) {
        if (actor == null || actor.isBlank()) {
            return "SYSTEM";
        }
        return actor.trim();
    }
}
