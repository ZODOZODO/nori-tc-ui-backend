package com.nori.tc.ui.core.deploy;

import java.util.Objects;

/**
 * Cancel 같은 deploy 액션 결과를 표현합니다.
 */
public record DeployActionResult(
        long deployKey,
        DeployStatus deployStatus,
        Boolean pauseRequested,
        String resultCode,
        DeployResultMessage resultMessage
) {

    public DeployActionResult {
        if (deployKey <= 0L) {
            throw new IllegalArgumentException("deployKey must be greater than 0");
        }
        deployStatus = Objects.requireNonNull(deployStatus, "deployStatus is null");
        if (resultCode == null || resultCode.isBlank()) {
            throw new IllegalArgumentException("resultCode must not be blank");
        }
        resultCode = resultCode.trim();
        resultMessage = Objects.requireNonNull(resultMessage, "resultMessage is null");
    }
}
