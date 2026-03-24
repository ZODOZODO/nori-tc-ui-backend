package com.nori.tc.ui.domain.model;

import com.nori.tc.ui.domain.common.model.UiDcopCollectionRule;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 DCOP Item 상세입니다.
 */
public record UiModelDcopItem(
        Long dcopItemKey,
        long modelVersionKey,
        String dcopItemName,
        String workflowName,
        String eventId,
        String variableId,
        UiDcopCollectionRule collectionRule,
        String calculationRule,
        Integer orderRule,
        OffsetDateTime updatedAt
) {
}
