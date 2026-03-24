package com.nori.tc.ui.domain.model;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 Event ID 상세입니다.
 */
public record UiModelEventId(
        long eventKey,
        long modelVersionKey,
        String eventId,
        String reportId,
        boolean enabled,
        String description,
        OffsetDateTime updatedAt
) {
}
