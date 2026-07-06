package com.nori.tc.ui.adapters.web.simulator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * orchestrator {@code RunResponse} 역직렬화 — 모달 폴링(시작/진행/종료) 표시용. orchestrator 가 추가 필드
 * (latencyStats 등)를 실어도 무시(unknown 관용)하도록 {@link JsonIgnoreProperties}. mode/state 는 enum 이
 * 문자열로 직렬화됨.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SimulatorRunResponse(
        String runId,
        String mode,
        String state,
        List<SimulatorTargetResponse> targets,
        // 이력/목록 표시용 append-only(R5). createdAt=ISO-8601 문자열(orchestrator Instant.toString()), scenarioId=시나리오 id.
        String createdAt,
        String scenarioId
) {

    /** 설비 target 관측 verdict — 모달 진행/종료 표시. commInterface=설비 통신타입(SOCKET/SECS/PLC, tc_eqp.comm_interface). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SimulatorTargetResponse(
            String eqpId,
            String state,
            String commInterface,
            int completedCycleCount,
            int erroredCycleCount,
            List<String> issuedLotRunIds
    ) {
    }
}
