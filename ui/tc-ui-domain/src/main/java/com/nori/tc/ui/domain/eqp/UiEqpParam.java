package com.nori.tc.ui.domain.eqp;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 설비 파라미터입니다.
 */
public record UiEqpParam(
        long eqpParamKey,
        long eqpKey,
        String paramName,
        String paramVersion,
        String paramValue,
        String description,
        String createdBy,
        OffsetDateTime updatedAt
) {
}
