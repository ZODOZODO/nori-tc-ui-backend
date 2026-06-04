package com.nori.tc.ui.core.livemap;

import tools.jackson.databind.JsonNode;

/**
 * 재사용 합성 객체(tc_live_map_object) 1행에 대응하는 도메인 record 입니다.
 *
 * <p>{@code shapeJson} 은 Konva Group 직렬화 트리({@code {v, root}})를 강타입 POJO 로 풀지 않고
 * {@link JsonNode} 로 그대로 통과시킵니다 — 새 도형/속성 추가 시 DB·서버 변경 0(SPEC JSONB 원칙).</p>
 */
public record LiveMapObject(
        long objectKey,
        String objectName,
        LiveMapObjectKind objectKind,
        JsonNode shapeJson,
        boolean isActive
) {
}
