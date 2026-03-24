package com.nori.tc.ui.domain.eqp;

import com.nori.tc.ui.domain.common.eqp.UiLogLevel;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 설비 로그 정책입니다.
 */
public record UiEqpLogPolicy(
        long eqpKey,
        UiLogLevel logLevel,
        int logRetentionDays,
        String logPath,
        OffsetDateTime updatedAt
) {
}
