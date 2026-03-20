package com.nori.tc.ui.core.deploy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * validate 결과입니다.
 */
public record DeployScenarioValidationResult(
        boolean success,
        OffsetDateTime processedAt,
        int loadedScenarioCount,
        List<String> validationErrors
) {

    public DeployScenarioValidationResult {
        processedAt = Objects.requireNonNull(processedAt, "processedAt is null");
        if (loadedScenarioCount < 0) {
            throw new IllegalArgumentException("loadedScenarioCount must not be negative");
        }
        validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
    }
}
