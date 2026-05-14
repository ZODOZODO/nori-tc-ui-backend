package com.nori.tc.ui.core.eqp;

import java.util.List;
import java.util.Map;

import com.nori.tc.ui.domain.model.UiModelParam;

/**
 * live-map 화면 전체에 필요한 batch prefetch 결과 묶음.
 *
 * <p>{@code LiveMapPrefetchPort#prefetch()} 의 단일 반환 타입이며, 어댑터가 6 sub-query 로 채워
 * service 가 메모리상에서 mapping 만 수행하도록 한다.</p>
 *
 * <p>필드:</p>
 * <ul>
 *   <li>{@code rows} : enabled EQP 마다 1행. EQP 가 없으면 빈 리스트.</li>
 *   <li>{@code modelParamsByModelVersionKey} : EQP-level 파라미터 fallback 용. distinct modelVersionKey
 *       기준 1회 batch 조회 결과를 modelVersionKey 별로 그룹화한 맵. 같은 modelVersionKey 를 공유하는
 *       여러 EQP 가 동일 reference 를 참조한다 (메모리 절약).</li>
 * </ul>
 */
public record LiveMapPrefetchSnapshot(
        List<LiveMapEqpPrefetchRow> rows,
        Map<Long, List<UiModelParam>> modelParamsByModelVersionKey
) {
}
