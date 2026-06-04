package com.nori.tc.ui.core.livemap;

import tools.jackson.databind.JsonNode;

/**
 * 레이아웃 PUT 시 저장할 배치 1건의 입력 모델입니다.
 *
 * @param objectKey     배치할 객체(tc_live_map_object) PK
 * @param eqpKey        EQUIPMENT 객체면 실설비 PK(필수), STRUCTURE 객체면 null
 * @param transformJson skew 기반 의사 2.5D 변환 JSONB
 */
public record UpsertLiveMapPlacement(
        long objectKey,
        Long eqpKey,
        JsonNode transformJson
) {
}
