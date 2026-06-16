package com.nori.tc.ui.adapters.web.simulator;

import java.util.List;

/**
 * ui-backend → orchestrator {@code POST /api/demo-runs} 요청. orchestrator {@code CreateRunRequest} 의 JSON
 * 형상과 일치(공유 contract 아님 — orchestrator 자체 REST DTO, R-8). demo-tester 형상과 달라 신규.
 *
 * @param mode ALL / EQUIPMENT_ONLY / UPSTREAM_ONLY (testType→mode 매핑 결과)
 * @param scenarioId 구동 시나리오 id
 * @param targets 설비 target 목록(선택 설비의 실 DB 설정, R-3)
 */
public record SimulatorCreateRunRequest(
        String mode,
        String scenarioId,
        List<SimulatorTargetRequest> targets
) {

    /**
     * @param eqpId 설비 식별자
     * @param commInterface SOCKET / PLC (DB {@code tc_eqp.comm_interface})
     * @param commMode gateway 기준 ACTIVE / PASSIVE (DB {@code tc_eqp.comm_mode}) — orchestrator 가 BIND/DIAL 로 반전
     * @param bindPort DB {@code tc_eqp.eqp_port} 실값(override 0)
     * @param portIds 설비 포트 id 목록
     */
    public record SimulatorTargetRequest(
            String eqpId,
            String commInterface,
            String commMode,
            int bindPort,
            List<String> portIds
    ) {
    }
}
