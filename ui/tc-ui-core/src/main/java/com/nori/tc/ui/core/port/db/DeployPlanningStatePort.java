package com.nori.tc.ui.core.port.db;

import com.nori.tc.ui.core.deploy.DeployCurrentState;

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
}
