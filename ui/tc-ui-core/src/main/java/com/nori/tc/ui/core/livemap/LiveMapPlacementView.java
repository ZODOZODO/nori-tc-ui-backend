package com.nori.tc.ui.core.livemap;

import tools.jackson.databind.JsonNode;

/**
 * 라인 캔버스에 놓인 객체 인스턴스(tc_live_map_placement) 1행입니다.
 *
 * <p>{@code eqpKey} 는 EQUIPMENT 객체 배치일 때만 non-null 입니다(STRUCTURE 는 null).
 * {@code transformJson} 은 skew 기반 의사 2.5D 변환({@code {v,x,y,rotation,scaleX/Y,skewX/Y,zIndex,depth}}).</p>
 */
public record LiveMapPlacementView(
        long placementKey,
        long objectKey,
        Long eqpKey,
        JsonNode transformJson
) {
}
