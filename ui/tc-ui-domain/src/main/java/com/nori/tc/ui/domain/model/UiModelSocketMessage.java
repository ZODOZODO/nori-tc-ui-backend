package com.nori.tc.ui.domain.model;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 SOCKET 메시지 상세입니다.
 */
public record UiModelSocketMessage(
        long socketMsgKey,
        long modelVersionKey,
        String socketMsgName,
        String description,
        OffsetDateTime updatedAt
) {
}
