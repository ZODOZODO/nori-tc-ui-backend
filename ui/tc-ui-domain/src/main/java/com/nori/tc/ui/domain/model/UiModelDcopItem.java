package com.nori.tc.ui.domain.model;

import com.nori.tc.ui.domain.common.model.UiDcopCollectionRule;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 DCOP Item 상세입니다.
 *
 * <p>Flow 바인딩은 별도 join table {@code tc_model_message_flow_dcop_item} 으로 분리된다 —
 * dcop_item 본체는 model_version 단위 pool 의 원소로 Flow 와 독립이다.</p>
 */
public record UiModelDcopItem(
        Long dcopItemKey,
        long modelVersionKey,
        String dcopItemName,
        String eventId,
        String variableId,
        UiDcopCollectionRule collectionRule,
        String calculationRule,
        Integer orderRule,
        OffsetDateTime updatedAt
) {
}
