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
     * deploy 성공 후 확정된 버전 3개만 EQP에 반영합니다.
     *
     * <p>계약: 나머지 컬럼은 <b>호출자가 넘긴 값이 아니라 저장 시점의 현재 행</b>을 유지합니다.
     * 확정이 stale snapshot 전량을 되쓰면 읽기와 쓰기 사이에 커밋된 UI 편집(lineKey·commMode·eqpIp·
     * eqpPort·log·프로토콜 설정)이 통째로 소실됩니다(S08 D-5).</p>
     *
     * @param eqpId 확정 대상 eqp id
     * @param modelVersionKey 확정된 모델 버전 키
     * @param appliedParamVersion 확정된 파라미터 버전
     * @param businessJarKey 확정된 business jar 키
     * @param actor 요청 사용자
     * @return 확정 후 최신 스냅샷
     */
    EqpManagementSnapshot finalizeDeployedVersions(
            String eqpId,
            long modelVersionKey,
            String appliedParamVersion,
            Long businessJarKey,
            String actor
    );

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
