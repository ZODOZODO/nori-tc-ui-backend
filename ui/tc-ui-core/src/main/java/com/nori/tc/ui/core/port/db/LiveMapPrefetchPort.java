package com.nori.tc.ui.core.port.db;

import com.nori.tc.ui.core.eqp.LiveMapPrefetchSnapshot;

/**
 * live-map 화면 전용 batch prefetch 포트.
 *
 * <p>단일 메서드 {@link #prefetch()} 호출 한 번으로 화면 렌더링에 필요한 모든 데이터를 6 sub-query
 * 안에 가져온다. 기존 {@link EqpManageQueryPort#findManageSnapshotByEqpId} 의 N×~10 sub-store
 * 패턴(54 EQP 일 때 ~540 회 호출) 을 대체하기 위해 도입되었다.</p>
 *
 * <p>호출 횟수:</p>
 * <ol>
 *   <li>tc_eqp (findAll)</li>
 *   <li>tc_eqp_state (IN eqp_key)</li>
 *   <li>tc_eqp_state_hist 최신 CONN (IN eqp_key)</li>
 *   <li>tc_eqp_param (IN eqp_key, PROCESS_TYPE / WAFER_MODE 만)</li>
 *   <li>tc_eqp_port_status (IN eqp_key)</li>
 *   <li>tc_model_param (IN distinct modelVersionKey, fallback 용)</li>
 * </ol>
 */
public interface LiveMapPrefetchPort {

    /**
     * live-map 화면에 필요한 데이터를 한 번에 prefetch 한다.
     *
     * @return enabled EQP 목록 + 각 EQP 의 param / port / runtime + 공유 modelParam 맵
     */
    LiveMapPrefetchSnapshot prefetch();
}
