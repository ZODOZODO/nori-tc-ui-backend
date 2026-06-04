package com.nori.tc.ui.adapters.web.dto.response;

import com.nori.tc.ui.core.livemap.LiveMapObject;
import tools.jackson.databind.JsonNode;

/**
 * 합성 객체 응답입니다.
 */
public record LiveMapObjectResponse(
        long objectKey,
        String objectName,
        String objectKind,
        JsonNode shapeJson,
        boolean isActive
) {

    public static LiveMapObjectResponse from(final LiveMapObject object) {
        return new LiveMapObjectResponse(
                object.objectKey(),
                object.objectName(),
                object.objectKind().name(),
                object.shapeJson(),
                object.isActive()
        );
    }
}
