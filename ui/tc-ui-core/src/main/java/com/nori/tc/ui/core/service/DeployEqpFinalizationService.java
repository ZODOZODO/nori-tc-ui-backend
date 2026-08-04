package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.deploy.DeployDeltaKind;
import com.nori.tc.ui.core.deploy.DeployDesiredState;
import com.nori.tc.ui.core.deploy.DeployTargetPlanDraft;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.exception.UiNotFoundException;
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

        // 이 조회는 desiredState 가 비운 필드의 기본값 산출 전용이다. 확정 자체는 포트가 row lock 안에서
        // 현재 행을 다시 읽어 3필드만 병합하므로, 이 스냅샷이 stale 해도 다른 컬럼을 되쓰지 않는다(S08 D-5).
        final EqpManagementSnapshot snapshot = persistenceService.findSnapshotByEqpId(planDraft.eqpId())
                .orElseThrow(() -> new UiNotFoundException("설비를 찾을 수 없습니다. eqpId=" + planDraft.eqpId()));
        final DeployDesiredState desiredState = planDraft.desiredState();

        persistenceService.finalizeDeployedVersions(
                planDraft.eqpId(),
                resolveModelVersionKey(desiredState, snapshot),
                resolveEqpParamVersion(desiredState, snapshot),
                resolveBusinessJarKey(desiredState, snapshot),
                normalizeActor(actor)
        );
    }

    private long resolveModelVersionKey(
            final DeployDesiredState desiredState,
            final EqpManagementSnapshot snapshot
    ) {
        return desiredState.modelVersionKey() != null && desiredState.modelVersionKey() > 0L
                ? desiredState.modelVersionKey()
                : snapshot.eqp().modelVersionKey();
    }

    private String resolveEqpParamVersion(
            final DeployDesiredState desiredState,
            final EqpManagementSnapshot snapshot
    ) {
        return desiredState.eqpParamVersion() != null && !desiredState.eqpParamVersion().isBlank()
                ? desiredState.eqpParamVersion().trim()
                : snapshot.eqp().appliedParamVersion();
    }

    private Long resolveBusinessJarKey(
            final DeployDesiredState desiredState,
            final EqpManagementSnapshot snapshot
    ) {
        return desiredState.businessJarRef() != null && desiredState.businessJarRef() > 0L
                ? desiredState.businessJarRef()
                : snapshot.eqp().businessJarKey();
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
