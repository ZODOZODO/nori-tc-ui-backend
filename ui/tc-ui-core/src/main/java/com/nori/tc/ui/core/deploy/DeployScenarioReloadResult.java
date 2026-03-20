package com.nori.tc.ui.core.deploy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * reload 결과입니다.
 */
public record DeployScenarioReloadResult(
        boolean success,
        int catalogVersionBefore,
        int catalogVersionAfter,
        int loadedScenarioCount,
        OffsetDateTime reloadedAt,
        List<String> validationErrors
) {

    public DeployScenarioReloadResult {
        if (catalogVersionBefore < 0) {
            throw new IllegalArgumentException("catalogVersionBefore must not be negative");
        }
        if (catalogVersionAfter < 0) {
            throw new IllegalArgumentException("catalogVersionAfter must not be negative");
        }
        if (loadedScenarioCount < 0) {
            throw new IllegalArgumentException("loadedScenarioCount must not be negative");
        }
        reloadedAt = Objects.requireNonNull(reloadedAt, "reloadedAt is null");
        validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
    }
}
