package com.nori.tc.ui.adapters.web.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 현재 적재된 scenario catalog 응답입니다.
 *
 * @param catalogVersion 현재 catalog version
 * @param catalogChecksum 현재 catalog checksum
 * @param loadedAt catalog loaded 시각
 * @param loadedBy catalog 반영 주체
 * @param reloadReason 마지막 반영 사유
 * @param scenarioCount scenario 수
 * @param scenarios scenario 요약 목록
 */
public record DeployScenarioCatalogResponse(
        int catalogVersion,
        String catalogChecksum,
        OffsetDateTime loadedAt,
        String loadedBy,
        String reloadReason,
        int scenarioCount,
        List<ScenarioSummary> scenarios
) {

    /**
     * scenario 요약 정보입니다.
     */
    public record ScenarioSummary(
            String code,
            String name,
            String summary,
            int version,
            int stepCount
    ) {
    }
}
