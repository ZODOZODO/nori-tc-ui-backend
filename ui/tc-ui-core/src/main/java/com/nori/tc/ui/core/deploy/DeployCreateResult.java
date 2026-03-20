package com.nori.tc.ui.core.deploy;

import java.util.Objects;

/**
 * deploy 생성 API 결과입니다.
 */
public record DeployCreateResult(
        long deployKey,
        DeployStatus deployStatus,
        String resultCode,
        DeployResultMessage resultMessage
) {

    public DeployCreateResult {
        if (deployKey <= 0) {
            throw new IllegalArgumentException("deployKey must be greater than 0");
        }
        deployStatus = Objects.requireNonNull(deployStatus, "deployStatus is null");
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
