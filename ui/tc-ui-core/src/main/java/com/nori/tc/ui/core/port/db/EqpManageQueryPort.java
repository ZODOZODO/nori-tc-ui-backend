package com.nori.tc.ui.core.port.db;

import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;

import java.util.Optional;

/**
 * EQP 관리 상세 조회 포트입니다.
 */
public interface EqpManageQueryPort {

    /**
     * EQP 관리 상세 스냅샷을 조회합니다.
     *
     * @param eqpId 조회 대상 eqp id
     * @return 스냅샷
     */
    Optional<EqpManagementSnapshot> findManageSnapshotByEqpId(String eqpId);
}
