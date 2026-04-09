package com.nori.tc.ui.adapters.web.dto.response;

/**
 * deploy summary polling 응답 DTO 입니다.
 */
public record DeploySummaryResponse(
        long deployKey,
        String deployName,
        String eventType,
        String resolvedScenarioCode,
        StateOverviewResponse asIsState,
        String deployStatus,
        boolean pauseRequested,
        String executionPolicy,
        String createdAt,
        String startedAt,
        String finishedAt,
        TargetSummaryResponse targetSummary,
        boolean terminal,
        boolean popupRequired,
        String resultCode,
        DeployResultMessageResponse resultMessage
) {

    /**
     * target 상태 집계 응답입니다.
     */
    public record TargetSummaryResponse(
            int total,
            int pending,
            int noChange,
            int running,
            int completed,
            int failed,
            int rollingBack,
            int rolledBack,
            int compensationFailed,
            int canceled
    ) {
    }

    /**
     * AS-IS / TO-BE 상태 요약 응답입니다.
     */
    public record StateOverviewResponse(
            String interfaceType,
            String modelName,
            String modelVersion,
            String businessJarName,
            String eqpParamVersion
    ) {
    }
}
