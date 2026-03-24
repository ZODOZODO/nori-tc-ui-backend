package com.nori.tc.ui.domain.model;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 Workflow 상세입니다.
 */
public record UiModelWorkflow(
        long workflowKey,
        long modelVersionKey,
        String workflowName,
        String messageName,
        String eventId,
        String transactionId,
        String workflowFilter,
        String actionName,
        String actionDataIndex,
        OffsetDateTime updatedAt
) {
}
