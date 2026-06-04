package com.nori.tc.ui.adapters.web.dto.request;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 라인 캔버스 저장 요청입니다(PUT /api/live-map/layout?lineKey=).
 *
 * <p>{@code canvasJson} 은 비어 있으면 null(빈 캔버스). placements 는 라인 캔버스에 놓일 배치 전체이며,
 * EQUIPMENT/STRUCTURE↔eqpKey 규칙과 스키마 버전 v 검증은 {@code LiveMapLayoutService} 가 수행합니다.</p>
 */
public record LiveMapLayoutPutRequest(
        JsonNode canvasJson,
        List<PlacementRequest> placements
) {

    public record PlacementRequest(
            long objectKey,
            Long eqpKey,
            JsonNode transformJson
    ) {
    }
}
