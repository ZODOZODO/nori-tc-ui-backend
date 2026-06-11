package com.nori.tc.ui.domain.eqp;

import com.nori.tc.ui.domain.common.eqp.UiControlState;
import com.nori.tc.ui.domain.common.eqp.UiEqpState;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 설비 런타임 상태입니다.
 */
public record UiEqpRuntimeState(
        long eqpKey,
        UiControlState controlState,
        UiEqpState eqpState,
        String desiredState,
        OffsetDateTime sinceAt,
        String reasonCode,
        String reasonDetail,
        OffsetDateTime updatedAt
) {
}
