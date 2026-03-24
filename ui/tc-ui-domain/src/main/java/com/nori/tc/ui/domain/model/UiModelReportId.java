package com.nori.tc.ui.domain.model;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 Report ID 상세입니다.
 */
public record UiModelReportId(
        long reportKey,
        long modelVersionKey,
        String reportId,
        String variableId,
        boolean enabled,
        String description,
        OffsetDateTime updatedAt
) {
}
