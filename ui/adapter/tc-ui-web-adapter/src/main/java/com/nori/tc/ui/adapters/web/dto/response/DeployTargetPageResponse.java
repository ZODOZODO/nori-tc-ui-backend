package com.nori.tc.ui.adapters.web.dto.response;

import java.util.List;

/**
 * deploy target 목록 polling 응답 DTO 입니다.
 */
public record DeployTargetPageResponse(
        long deployKey,
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<DeployTargetResponse> targets
) {

    /**
     * target 단건 응답입니다.
     */
    public record DeployTargetResponse(
            long deployTargetKey,
            String eqpId,
            String targetStatus,
            Integer currentStepOrder,
            String currentStepCode,
            String currentStepStatus,
            boolean rollbackRequired,
            String lastErrorCode,
            String lastErrorMessage,
            String updatedAt
    ) {
    }
}
