package com.nori.tc.ui.adapters.web.demo;

import java.time.OffsetDateTime;

public record DemoJobSummaryResponse(
        String jobId,
        String eqpId,
        String eqpName,
        String processType,
        String portId,
        String testType,
        String scenarioId,
        int scenarioVersion,
        String scenarioName,
        String state,
        String currentStageCode,
        String currentStageName,
        int progressPercent,
        int currentStepCount,
        int totalStepCount,
        String message,
        OffsetDateTime requestedAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime updatedAt,
        String failureCode,
        String failureMessage
) {
}
