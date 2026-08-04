package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.deploy.DeployDeltaKind;
import com.nori.tc.ui.core.deploy.DeployDesiredState;
import com.nori.tc.ui.core.deploy.DeployTargetPlanDraft;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.eqp.UiEqp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * S08 D-5 — deploy 확정이 stale snapshot 으로 EQP 전 컬럼을 되쓰지 않는지 검증합니다.
 *
 * <p>확정이 실제로 바꿔야 하는 값은 modelVersionKey / appliedParamVersion / businessJarKey 3개뿐이다.
 * 읽기(txn A)와 쓰기(txn B)가 분리된 상태에서 stale snapshot 전량을 되쓰면 그 사이에 커밋된
 * UI 편집(lineKey·commMode·eqpIp·eqpPort·log·프로토콜 설정)이 통째로 소실된다.</p>
 */
class DeployEqpFinalizationServiceTest {

    @Test
    @DisplayName("S08 D-5: 확정은 3개 버전 필드만 바꾸고 사이에 커밋된 UI 편집을 덮어쓰지 않습니다")
    void finalizeKeepsConcurrentUiEditAndAppliesOnlyVersionFields() {
        final MutatingEqpCrudPort crudPort = new MutatingEqpCrudPort();
        final DeployEqpFinalizationService service =
                new DeployEqpFinalizationService(new EqpPersistenceService(crudPort));

        service.finalizeTargetSuccess(target(777L, "PARAM_V2", 4002L), "deployer");

        final UiEqp stored = crudPort.stored;
        // 확정 대상 3개는 반영된다.
        assertEquals(777L, stored.modelVersionKey());
        assertEquals("PARAM_V2", stored.appliedParamVersion());
        assertEquals(4002L, stored.businessJarKey());
        // 읽기 이후 커밋된 UI 편집은 살아 있어야 한다(stale 값 10.10.10.1 / 6000 으로 원복 금지).
        assertEquals("10.10.10.9", stored.eqpIp());
        assertEquals(6009, stored.eqpPort());
        assertEquals("PASSIVE", stored.commMode());
    }

    private static PersistedDeployTarget target(
            final long modelVersionKey,
            final String eqpParamVersion,
            final long businessJarRef
    ) {
        final DeployDesiredState desiredState = new DeployDesiredState(
                "SOCKET",
                modelVersionKey,
                businessJarRef,
                null,
                eqpParamVersion,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        final DeployTargetPlanDraft planDraft = new DeployTargetPlanDraft(
                "EQP-FIN-001",
                "SOCKET",
                null,
                desiredState,
                Set.of(DeployDeltaKind.MODEL_CHANGED),
                "SCENARIO",
                1,
                List.of(),
                null,
                null,
                null,
                false
        );
        return new PersistedDeployTarget(1L, 1, planDraft);
    }

    private static UiEqp eqp(
            final String commMode,
            final String eqpIp,
            final int eqpPort,
            final long modelVersionKey,
            final String appliedParamVersion,
            final Long businessJarKey
    ) {
        final OffsetDateTime now = OffsetDateTime.parse("2026-08-04T00:00:00Z");
        return new UiEqp(
                11L,
                "EQP-FIN-001",
                1L,
                UiProtocolType.SOCKET,
                commMode,
                false,
                3,
                eqpIp,
                eqpPort,
                modelVersionKey,
                appliedParamVersion,
                3001L,
                businessJarKey,
                true,
                now,
                now,
                "SYSTEM",
                "SYSTEM"
        );
    }

    private static EqpManagementSnapshot snapshot(final UiEqp eqp) {
        return new EqpManagementSnapshot(
                eqp, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), null, null
        );
    }

    /**
     * 조회 직후 다른 트랜잭션(UI 편집)이 커밋된 상황을 모사합니다.
     *
     * <p>{@code findSnapshotByEqpId} 는 편집 전 값을 반환하고, 이후 저장 시점의 현재 행은 편집 후 값이다.
     * 확정 경로가 자기 stale snapshot 을 전 컬럼에 되쓰면 편집이 소실된다.</p>
     *
     * <p>어댑터의 실제 보증(row lock 안에서 재조회 후 3필드만 병합)은
     * {@code JpaEqpCrudPortTest#finalizeDeployedVersions*} 가 검증한다.</p>
     */
    private static final class MutatingEqpCrudPort implements EqpCrudPort {

        private UiEqp stored = eqp("PASSIVE", "10.10.10.9", 6009, 500L, "PARAM_V1", 4001L);

        @Override
        public Optional<EqpManagementSnapshot> findSnapshotByEqpId(final String eqpId) {
            // 편집 전(stale) 값을 반환한다.
            return Optional.of(snapshot(eqp("ACTIVE", "10.10.10.1", 6000, 500L, "PARAM_V1", 4001L)));
        }

        @Override
        public EqpManagementSnapshot finalizeDeployedVersions(
                final String eqpId,
                final long modelVersionKey,
                final String appliedParamVersion,
                final Long businessJarKey,
                final String actor
        ) {
            // 포트 계약: 현재 행에 3개 버전 필드만 병합한다.
            stored = new UiEqp(
                    stored.eqpKey(),
                    stored.eqpId(),
                    stored.lineKey(),
                    stored.commInterface(),
                    stored.commMode(),
                    stored.isDev(),
                    stored.routePartition(),
                    stored.eqpIp(),
                    stored.eqpPort(),
                    modelVersionKey,
                    appliedParamVersion,
                    stored.gatewayJarKey(),
                    businessJarKey,
                    stored.enabled(),
                    stored.createdAt(),
                    stored.updatedAt(),
                    stored.createdBy(),
                    actor
            );
            return snapshot(stored);
        }

        @Override
        public EqpManagementSnapshot create(final EqpManagementCommand.Create command) {
            throw new UnsupportedOperationException("이 테스트에서 사용하지 않습니다.");
        }

        @Override
        public EqpManagementSnapshot update(final String eqpId, final EqpManagementCommand.Update command) {
            // 결함 재현: 호출자가 stale snapshot 으로 만든 전-컬럼 명령이 그대로 반영된다.
            stored = new UiEqp(
                    stored.eqpKey(),
                    stored.eqpId(),
                    command.lineKey(),
                    stored.commInterface(),
                    command.commMode(),
                    command.isDev(),
                    command.routePartition(),
                    command.eqpIp(),
                    command.eqpPort(),
                    command.modelVersionKey(),
                    command.appliedParamVersion(),
                    command.gatewayJarRef(),
                    command.businessJarRef(),
                    stored.enabled(),
                    stored.createdAt(),
                    stored.updatedAt(),
                    stored.createdBy(),
                    command.actor()
            );
            return snapshot(stored);
        }

        @Override
        public void restore(final EqpManagementSnapshot snapshot) {
            throw new UnsupportedOperationException("이 테스트에서 사용하지 않습니다.");
        }

        @Override
        public void delete(final String eqpId) {
            throw new UnsupportedOperationException("이 테스트에서 사용하지 않습니다.");
        }
    }
}
