package com.nori.tc.ui.core.deploy;

import java.util.Objects;

/**
 * Deploy 이력 삭제 결과를 표현합니다.
 */
public record DeployDeleteResult(
        long deployKey,
        boolean deleted,
        String resultCode,
        DeployResultMessage resultMessage
) {

    public DeployDeleteResult {
        if (deployKey <= 0L) {
            throw new IllegalArgumentException("deployKey must be greater than 0");
        }
        if (resultCode == null || resultCode.isBlank()) {
            throw new IllegalArgumentException("resultCode must not be blank");
        }
        resultCode = resultCode.trim();
        resultMessage = Objects.requireNonNull(resultMessage, "resultMessage is null");
    }
}
