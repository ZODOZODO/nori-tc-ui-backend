package com.nori.tc.ui.core.deploy;

import com.nori.tc.ui.core.model.UiCommandEventType;

import java.time.OffsetDateTime;

/**
 * Deploy / Deploy_History 목록 화면의 서버 조회 단건 뷰입니다.
 */
public record DeployHistoryView(
        long deployKey,
        String deployName,
        UiCommandEventType eventType,
        DeployStatus deployStatus,
        DeployExecutionPolicy executionPolicy,
        String requestedBy,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        boolean terminal,
        String interfaceType,
        String selectedModelName,
        String selectedModelVersion,
        String selectedBusinessJarName,
        DeployTargetCounts targetSummary
) {
}
