package com.nori.tc.ui.domain.model;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 모델 파라미터 상세입니다.
 */
public record UiModelParam(
        long modelParamKey,
        long modelVersionKey,
        String paramName,
        String paramValue,
        String description,
        OffsetDateTime updatedAt
) {
}
