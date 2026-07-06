package com.nori.tc.ui.core.port.db;

import com.nori.tc.ui.core.deploy.DeployCurrentState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * deploy planner가 current state를 조회하는 포트입니다.
 */
public interface DeployPlanningStatePort {

    /**
     * eqpId 기준 current state 를 조회합니다.
     *
     * @param eqpId 조회 대상 설비 ID
     * @return current state(Optional)
     */
    Optional<DeployCurrentState> findCurrentStateByEqpId(String eqpId);

    /**
     * preview 전용 경량 배치 조회 — equipmentProfile(heavy)을 로드하지 않는 current state 를
     * eqpId 기준 Map 으로 반환합니다. 미존재 설비는 Map 에서 부재.
     *
     * <p>default 구현은 단건 {@link #findCurrentStateByEqpId(String)} 를 loop 합니다(테스트 stub 호환).
     * 실 어댑터는 IN-절 배치 SQL 로 override 하여 N+1 을 제거합니다.</p>
     *
     * @param eqpIds 조회 대상 설비 ID 목록
     * @return eqpId → current state(경량)
     */
    default Map<String, DeployCurrentState> findCurrentLightStatesByEqpIds(final List<String> eqpIds) {
        final Map<String, DeployCurrentState> result = new LinkedHashMap<>();
        for (final String eqpId : eqpIds) {
            findCurrentStateByEqpId(eqpId).ifPresent(state -> result.put(eqpId, state));
        }
        return result;
    }
}
