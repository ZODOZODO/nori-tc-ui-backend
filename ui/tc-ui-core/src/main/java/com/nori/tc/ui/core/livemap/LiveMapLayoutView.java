package com.nori.tc.ui.core.livemap;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 라인 1개의 캔버스 조회 결과(GET /api/live-map/layout)입니다.
 *
 * <p>{@code objects} 는 placement 들이 참조하는 객체 원형을 중복 제거해 동봉합니다 —
 * placement 마다 shape_json 을 반복 전송하지 않고도 비-admin 클라이언트가 자족적으로 렌더할 수 있습니다.</p>
 *
 * @param layoutKey  신규(미저장) 라인이면 null
 * @param canvasJson 비어 있으면 null(프론트 기본값 적용)
 */
public record LiveMapLayoutView(
        Long layoutKey,
        long lineKey,
        JsonNode canvasJson,
        List<LiveMapPlacementView> placements,
        List<LiveMapObject> objects
) {
}
