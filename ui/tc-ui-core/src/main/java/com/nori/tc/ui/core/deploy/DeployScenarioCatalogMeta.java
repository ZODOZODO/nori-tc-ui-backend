package com.nori.tc.ui.core.deploy;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * catalog version/checksum DB 메타입니다.
 */
public record DeployScenarioCatalogMeta(
        int catalogVersion,
        String catalogChecksum,
        int scenarioCount,
        OffsetDateTime loadedAt,
        String loadedBy,
        String reloadReason
) {

    public DeployScenarioCatalogMeta {
        if (catalogVersion <= 0) {
            throw new IllegalArgumentException("catalogVersion must be greater than 0");
        }
        if (scenarioCount < 0) {
            throw new IllegalArgumentException("scenarioCount must not be negative");
        }
        catalogChecksum = requireText("catalogChecksum", catalogChecksum);
        loadedAt = Objects.requireNonNull(loadedAt, "loadedAt is null");
        loadedBy = requireText("loadedBy", loadedBy);
        reloadReason = requireText("reloadReason", reloadReason);
    }

    private static String requireText(final String fieldName, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
