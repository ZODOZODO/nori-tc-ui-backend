package com.nori.tc.ui.core.deploy;

import com.nori.tc.ui.core.model.UiCommandEventType;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * DB 조회 결과를 옮기는 deploy summary 스냅샷입니다.
 */
public record DeploySummarySnapshot(
        long deployKey,
        String deployName,
        UiCommandEventType eventType,
        DeployStatus deployStatus,
        boolean pauseRequested,
        DeployExecutionPolicy executionPolicy,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        DeployTargetCounts targetSummary,
        String lastErrorCode,
        String lastErrorMessage
) {

    public DeploySummarySnapshot {
        if (deployKey <= 0) {
            throw new IllegalArgumentException("deployKey must be greater than 0");
        }
        deployName = requireText("deployName", deployName);
        eventType = Objects.requireNonNull(eventType, "eventType is null");
        deployStatus = Objects.requireNonNull(deployStatus, "deployStatus is null");
        executionPolicy = Objects.requireNonNull(executionPolicy, "executionPolicy is null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt is null");
        targetSummary = Objects.requireNonNull(targetSummary, "targetSummary is null");
    }

    private static String requireText(final String fieldName, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
