package com.nori.tc.ui.adapters.web.simulator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * orchestrator {@code GET /api/scenarios} 카탈로그 1 건 역직렬화(R-5). 모달 시나리오 드롭다운 소스 + 선택 설비
 * comm_interface 필터.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SimulatorScenarioCatalogItem(
        String scenarioId,
        String name,
        int version,
        String commInterface,
        List<String> supportedModes,
        boolean isSeed
) {
}
