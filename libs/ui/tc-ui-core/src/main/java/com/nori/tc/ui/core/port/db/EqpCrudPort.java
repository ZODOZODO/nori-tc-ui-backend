package com.nori.tc.ui.core.port.db;

import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;

import java.util.Optional;

/**
 * EQP 관리 DB 저장/복구 포트입니다.
 */
public interface EqpCrudPort {

    /**
     * EQP 생성 후 최신 스냅샷을 반환합니다.
     *
     * @param command 생성 명령
     * @return 저장 완료 스냅샷
     */
    EqpManagementSnapshot create(EqpManagementCommand.Create command);

    /**
     * EQP 수정 후 최신 스냅샷을 반환합니다.
     *
     * @param eqpId 수정 대상 eqp id
     * @param command 수정 명령
     * @return 저장 완료 스냅샷
     */
    EqpManagementSnapshot update(String eqpId, EqpManagementCommand.Update command);

    /**
     * EQP 스냅샷을 다시 저장해 보상 복구합니다.
     *
     * @param snapshot 복구 대상 스냅샷
     */
    void restore(EqpManagementSnapshot snapshot);

    /**
     * EQP를 삭제합니다.
     *
     * @param eqpId 삭제 대상 eqp id
     */
    void delete(String eqpId);

    /**
     * EQP 현재 스냅샷을 조회합니다.
     *
     * @param eqpId 조회 대상 eqp id
     * @return 스냅샷
     */
    Optional<EqpManagementSnapshot> findSnapshotByEqpId(String eqpId);
}
