package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * §7.3/§18.1 영향 집합 산출 규칙을 고정합니다.
 *
 * <p>P1 Business reconciler 가 prepare 에서 정렬과 폐쇄를 다시 검증하므로, 여기서 규칙을 어기면
 * operation 이 아예 PASS 되지 않습니다 — 그래서 생산 지점에서 먼저 고정합니다.</p>
 */
class UiResourceKeyPlannerTest {

    @Test
    @DisplayName("model 공유 상태를 바꾸는 변경은 같은 modelVersionKey 설비를 전부 포함한다")
    void modelSharedChangeClosesOverAllEquipmentOfThatVersion() {
        final UiResourceKeyPlanner planner = new UiResourceKeyPlanner(
                eqpQueryPort(List.of("EQP003", "EQP001", "EQP002")));

        final List<String> keys = planner.plan(UiCommandEventType.EQP_MODEL_UPDATE, "EQP001", Set.of(7L));

        assertEquals(
                List.of("equipment:EQP001", "equipment:EQP002", "equipment:EQP003"),
                keys,
                "일부만 잠그면 나머지 설비가 옛 model 로 처리를 계속합니다.");
    }

    @Test
    @DisplayName("결과는 항상 lexicographic 정렬이다")
    void resultIsLexicographicallySorted() {
        final UiResourceKeyPlanner planner = new UiResourceKeyPlanner(
                eqpQueryPort(List.of("EQP_B", "EQP_A")));

        assertEquals(
                List.of("equipment:EQP_A", "equipment:EQP_B", "equipment:EQP_Z"),
                planner.plan(UiCommandEventType.EQP_BUSINESS_JARFILE_UPDATE, "EQP_Z", Set.of(7L)));
    }

    @Test
    @DisplayName("공유 상태를 건드리지 않는 변경은 대상 설비만 포함한다")
    void nonSharedChangeAffectsOnlyTheTarget() {
        final UiResourceKeyPlanner planner = new UiResourceKeyPlanner(
                eqpQueryPort(List.of("EQP002", "EQP003")));

        assertEquals(
                List.of("equipment:EQP001"),
                planner.plan(UiCommandEventType.EQP_UPDATE, "EQP001", Set.of(7L)));
    }

    @Test
    @DisplayName("공유 상태 변경인데 modelVersionKey 를 모르면 접수하지 않는다")
    void unknownModelVersionKeyRejectsAcceptance() {
        final UiResourceKeyPlanner planner = new UiResourceKeyPlanner(eqpQueryPort(List.of()));

        assertThrows(IllegalStateException.class,
                () -> planner.plan(UiCommandEventType.EQP_MODEL_UPDATE, "EQP001", Set.of()));
    }

    @Test
    @DisplayName("resourceKey 에서 설비 id 를 되읽을 수 있다")
    void eqpIdIsRecoverableFromResourceKey() {
        assertEquals("EQP001", UiResourceKeyPlanner.eqpIdOf("equipment:EQP001"));
        assertThrows(IllegalArgumentException.class, () -> UiResourceKeyPlanner.eqpIdOf("EQP001"));
    }

    private static EqpQueryPort eqpQueryPort(final List<String> sharedEqpIds) {
        return new EqpQueryPort() {
            @Override
            public PagedResponse<UiEqp> findAll(final UiPageRequest pageRequest) {
                return PagedResponse.empty(0, 1);
            }

            @Override
            public Optional<UiEqp> findByEqpId(final String eqpId) {
                return Optional.empty();
            }

            @Override
            public List<String> findParamVersionsByEqpId(final String eqpId) {
                return List.of();
            }

            @Override
            public List<UiEqpParam> findParamsByEqpIdAndVersion(final String eqpId, final String version) {
                return List.of();
            }

            @Override
            public Optional<EqpCheckoutStateView> findCheckoutStateByEqpId(final String eqpId) {
                return Optional.empty();
            }

            @Override
            public Optional<EqpRuntimeStateView> findRuntimeStateByEqpId(final String eqpId) {
                return Optional.empty();
            }

            @Override
            public List<String> findEqpIdsByModelVersionKey(final long modelVersionKey) {
                return sharedEqpIds;
            }
        };
    }
}
