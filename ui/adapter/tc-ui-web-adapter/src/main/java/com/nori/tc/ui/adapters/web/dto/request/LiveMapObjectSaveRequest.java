package com.nori.tc.ui.adapters.web.dto.request;

import tools.jackson.databind.JsonNode;

/**
 * 합성 객체 저장/수정 요청입니다(POST/PUT /api/live-map/layout/objects).
 *
 * <p>{@code shapeJson} 은 konva Group 직렬화 트리({@code {v, root}})를 그대로 통과시킵니다.
 * 필드 검증(이름/종류/스키마 버전 v)은 {@code LiveMapObjectService} 가 수행합니다.</p>
 */
public record LiveMapObjectSaveRequest(
        String objectName,
        String objectKind,
        JsonNode shapeJson
) {
}
