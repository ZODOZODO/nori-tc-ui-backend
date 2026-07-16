package com.nori.tc.ui.adapters.web.dto.response;

/**
 * 설비 상세 화면 상태 표시용 런타임 상태 응답 DTO입니다.
 *
 * @param controlState 제어 상태(tc_eqp_state.control_state)
 * @param eqpState 설비 상태(tc_eqp_state.eqp_state)
 * @param connectionState 연결 상태(tc_eqp_state_hist 최신 CONN.to_state)
 * @param desiredState 연결 의도(tc_eqp_state.desired_state, STARTED/ENDED, null=미확정).
 *                     control_state 와 함께 노출해 stuck(STARTED+끊김)을 식별 가능하게 한다(R-C1).
 */
public record EqpRuntimeStateResponse(
        String controlState,
        String eqpState,
        String connectionState,
        String desiredState
) {
}
