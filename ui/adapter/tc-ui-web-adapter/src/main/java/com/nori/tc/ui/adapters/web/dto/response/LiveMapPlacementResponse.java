package com.nori.tc.ui.adapters.web.dto.response;

import com.nori.tc.ui.core.livemap.LiveMapPlacementView;
import tools.jackson.databind.JsonNode;

/**
 * 라인 캔버스에 놓인 객체 인스턴스 응답입니다.
 */
public record LiveMapPlacementResponse(
        long placementKey,
        long objectKey,
        Long eqpKey,
        JsonNode transformJson
) {

    public static LiveMapPlacementResponse from(final LiveMapPlacementView placement) {
        return new LiveMapPlacementResponse(
                placement.placementKey(),
                placement.objectKey(),
                placement.eqpKey(),
                placement.transformJson()
        );
    }
}
