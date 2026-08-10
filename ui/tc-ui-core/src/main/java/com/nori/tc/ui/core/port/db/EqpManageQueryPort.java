package com.nori.tc.ui.core.port.db;

import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;

import java.util.List;
import java.util.Map;
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

    /**
     * 여러 EQP 의 관리 상세 스냅샷을 한 번에 조회합니다.
     *
     * <p>{@link #findManageSnapshotByEqpId(String)} 를 설비마다 부르면 설비 수 × 서브 store 수만큼
     * DB 왕복이 생깁니다. 다건 화면은 이 메서드를 써서 고정 횟수로 조회합니다.</p>
     *
     * @param eqpIds 조회 대상 eqp id 목록
     * @return eqpId → 스냅샷 (미존재 설비는 결과에서 빠짐)
     */
    Map<String, EqpManagementSnapshot> findManageSnapshotsByEqpIds(List<String> eqpIds);
}
