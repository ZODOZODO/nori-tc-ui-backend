package com.nori.tc.ui.adapters.web.dto.response;

import com.nori.tc.ui.core.livemap.LiveMapLayoutView;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 라인 1개의 캔버스 조회/저장 응답입니다.
 *
 * <p>{@code objects} 는 placement 들이 참조하는 객체 원형(중복 제거)이라 클라이언트가
 * placement 마다 shape_json 을 반복 수신하지 않고도 자족 렌더할 수 있습니다.</p>
 */
public record LiveMapLayoutResponse(
        Long layoutKey,
        long lineKey,
        JsonNode canvasJson,
        List<LiveMapPlacementResponse> placements,
        List<LiveMapObjectResponse> objects
) {

    public static LiveMapLayoutResponse from(final LiveMapLayoutView view) {
        return new LiveMapLayoutResponse(
                view.layoutKey(),
                view.lineKey(),
                view.canvasJson(),
                view.placements().stream().map(LiveMapPlacementResponse::from).toList(),
                view.objects().stream().map(LiveMapObjectResponse::from).toList()
        );
    }
}
