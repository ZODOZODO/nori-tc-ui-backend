package com.nori.tc.ui.domain.eqp;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 HSMS 상세 설정입니다.
 */
public record UiEqpHsms(
        long eqpKey,
        int deviceId,
        int t3Timeout,
        int t5Timeout,
        int t6Timeout,
        int t7Timeout,
        int t8Timeout,
        boolean linkTestEnabled,
        int linkTestInterval,
        long maxMsgBytes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
