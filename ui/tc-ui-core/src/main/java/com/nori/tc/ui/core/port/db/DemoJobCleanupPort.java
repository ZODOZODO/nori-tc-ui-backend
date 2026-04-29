package com.nori.tc.ui.core.port.db;

import java.util.List;

/**
 * demo job 정합성 검증 및 정리 포트입니다.
 *
 * <p>nori-tc-demo-tester가 관리하는 tc_demo_job 테이블이 tc_eqp(eqp_key) FK를 참조하므로,
 * EQP 삭제 시 활성 demo job 존재 여부 사전 검증과 종료 상태 demo job 정리를 담당합니다.</p>
 */
public interface DemoJobCleanupPort {

    /**
     * 지정한 eqp_key를 참조하는 활성 상태(QUEUED, PREPARING, SYNCING_SPEC, CONNECTING,
     * RUNNING, CLEANING_UP, CANCEL_REQUESTED) demo job ID 목록을 조회합니다.
     *
     * @param eqpKey 대상 eqp_key
     * @return 활성 demo job ID 목록 (없으면 빈 리스트)
     */
    List<String> findActiveDemoJobIds(long eqpKey);

    /**
     * 지정한 eqp_key를 참조하는 모든 demo job(종료 상태 포함)을 삭제합니다.
     *
     * <p>tc_demo_job_session, tc_demo_job_event는 ON DELETE CASCADE로 함께 정리됩니다.
     * 활성 job이 존재할 경우 호출자가 사전에 거부해야 합니다.</p>
     *
     * @param eqpKey 대상 eqp_key
     * @return 삭제된 row 수
     */
    int deleteAllByEqpKey(long eqpKey);
}
