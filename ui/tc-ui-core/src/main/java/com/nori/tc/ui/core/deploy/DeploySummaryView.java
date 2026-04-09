package com.nori.tc.ui.core.deploy;

import com.nori.tc.ui.core.model.UiCommandEventType;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * polling summary API가 반환하는 deploy view 입니다.
 */
public record DeploySummaryView(
        long deployKey,
        String deployName,
        UiCommandEventType eventType,
        String resolvedScenarioCode,
        DeployStateOverview asIsState,
        DeployStatus deployStatus,
        boolean pauseRequested,
        DeployExecutionPolicy executionPolicy,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        DeployTargetCounts targetSummary,
        boolean terminal,
        boolean popupRequired,
        String resultCode,
        DeployResultMessage resultMessage
) {

    public DeploySummaryView {
        if (deployKey <= 0) {
            throw new IllegalArgumentException("deployKey must be greater than 0");
        }
        deployName = requireText("deployName", deployName);
        eventType = Objects.requireNonNull(eventType, "eventType is null");
        deployStatus = Objects.requireNonNull(deployStatus, "deployStatus is null");
        executionPolicy = Objects.requireNonNull(executionPolicy, "executionPolicy is null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt is null");
        targetSummary = Objects.requireNonNull(targetSummary, "targetSummary is null");
        resultCode = requireText("resultCode", resultCode);
        resultMessage = Objects.requireNonNull(resultMessage, "resultMessage is null");
    }

    private static String requireText(final String fieldName, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
