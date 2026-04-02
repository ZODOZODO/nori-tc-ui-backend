package com.nori.tc.ui.core.deploy;

import java.util.Objects;

/**
 * active deploy 와 충돌하는 설비 정보를 표현합니다.
 */
public record DeployActiveEqpConflict(
        String eqpId,
        long deployKey,
        DeployStatus deployStatus,
        String deployName,
        String requestedBy
) {

    public DeployActiveEqpConflict {
        eqpId = requireText("eqpId", eqpId);
        deployStatus = Objects.requireNonNull(deployStatus, "deployStatus is null");
        deployName = requireText("deployName", deployName);
        requestedBy = requireText("requestedBy", requestedBy);
    }

    private static String requireText(final String fieldName, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
