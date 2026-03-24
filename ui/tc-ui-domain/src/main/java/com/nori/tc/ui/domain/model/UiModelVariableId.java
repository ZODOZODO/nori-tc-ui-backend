package com.nori.tc.ui.domain.model;

import com.nori.tc.ui.domain.common.model.UiVariableIdType;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 Variable ID 상세입니다.
 */
public record UiModelVariableId(
        long variableKey,
        long modelVersionKey,
        String variableId,
        UiVariableIdType variableIdType,
        String description,
        OffsetDateTime updatedAt
) {
}
