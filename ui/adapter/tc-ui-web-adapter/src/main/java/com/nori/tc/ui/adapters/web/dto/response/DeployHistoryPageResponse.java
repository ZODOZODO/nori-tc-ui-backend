package com.nori.tc.ui.adapters.web.dto.response;

import java.util.List;

/**
 * Deploy / Deploy_History 목록 응답 DTO 입니다.
 */
public record DeployHistoryPageResponse(
        List<DeployHistoryResponse> items,
        int offset,
        int limit,
        long count
) {

    /**
     * 목록 단건 응답입니다.
     */
    public record DeployHistoryResponse(
            long deployKey,
            String deployName,
            String eventType,
            String deployStatus,
            boolean pauseRequested,
            String executionPolicy,
            String requestedBy,
            String createdAt,
            String startedAt,
            String finishedAt,
            boolean terminal,
            String interfaceType,
            String selectedModelName,
            String selectedModelVersion,
            String selectedBusinessJarName,
            DeploySummaryResponse.TargetSummaryResponse targetSummary
    ) {
    }
}
