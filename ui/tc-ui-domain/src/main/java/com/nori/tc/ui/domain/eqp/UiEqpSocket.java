package com.nori.tc.ui.domain.eqp;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 SOCKET 상세 설정입니다.
 */
public record UiEqpSocket(
        long eqpKey,
        String socketProtocolType,
        String charset,
        boolean heartbeatEnabled,
        int heartbeatInterval,
        int readTimeout,
        int writeTimeout,
        int maxFrameSizeBytes,
        boolean keepAliveEnabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
