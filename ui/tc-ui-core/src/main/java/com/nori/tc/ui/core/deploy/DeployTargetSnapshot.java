package com.nori.tc.ui.core.deploy;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * deploy target 목록 조회용 스냅샷입니다.
 */
public record DeployTargetSnapshot(
        long deployTargetKey,
        String eqpId,
        DeployTargetStatus targetStatus,
        Integer currentStepOrder,
        String currentStepCode,
        DeployStepStatus currentStepStatus,
        boolean rollbackRequired,
        String lastErrorCode,
        String lastErrorMessage,
        OffsetDateTime updatedAt
) {

    public DeployTargetSnapshot {
        if (deployTargetKey <= 0) {
            throw new IllegalArgumentException("deployTargetKey must be greater than 0");
        }
        eqpId = requireText("eqpId", eqpId);
        targetStatus = Objects.requireNonNull(targetStatus, "targetStatus is null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt is null");
    }

    private static String requireText(final String fieldName, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
