package com.nori.tc.ui.core.eqp;

import java.util.List;

import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpParam;
import com.nori.tc.ui.domain.eqp.UiEqpPortStatus;

/**
 * live-map 화면 1개 설비에 필요한 batch prefetch 결과 1행.
 *
 * <p>{@link LiveMapPrefetchSnapshot} 의 구성 요소이며, 단일 EQP 에 대한 화면 렌더링에 필요한
 * 데이터를 한 번의 batch 호출로 묶어 담는다.</p>
 *
 * <p>필드:</p>
 * <ul>
 *   <li>{@code eqp} : 설비 기본 스냅샷 (eqpId / modelVersionKey / appliedParamVersion 등)</li>
 *   <li>{@code lineCode} : 설비 소속 tc_line.line_code (live-map lineValue). NOT NULL FK 보장으로 항상 존재한다.</li>
 *   <li>{@code lineName} : 설비 소속 tc_line.line_name (live-map lineLabel).</li>
 *   <li>{@code params} : 해당 EQP 의 tc_eqp_param 중 PROCESS_TYPE / WAFER_MODE 만 필터링된 목록.
 *       비어 있으면 model_param fallback 으로 사용한다.</li>
 *   <li>{@code portStatuses} : 해당 EQP 의 tc_eqp_port_status 전체 목록.</li>
 *   <li>{@code runtimeState} : tc_eqp_state + tc_eqp_state_hist 최신 CONN 으로 합성된 런타임 상태.</li>
 * </ul>
 */
public record LiveMapEqpPrefetchRow(
        UiEqp eqp,
        String lineCode,
        String lineName,
        List<UiEqpParam> params,
        List<UiEqpPortStatus> portStatuses,
        EqpQueryPort.EqpRuntimeStateView runtimeState
) {
}
