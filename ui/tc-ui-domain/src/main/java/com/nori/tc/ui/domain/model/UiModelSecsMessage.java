package com.nori.tc.ui.domain.model;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 SECS 메시지 상세입니다.
 */
public record UiModelSecsMessage(
        long secsMsgKey,
        long modelVersionKey,
        String secsMsgName,
        String description,
        OffsetDateTime updatedAt
) {
}
