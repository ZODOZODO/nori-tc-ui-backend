package com.nori.tc.ui.core.deploy;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 메모리에 적재된 immutable scenario catalog 입니다.
 */
public record DeployScenarioCatalog(
        int catalogVersion,
        String catalogChecksum,
        OffsetDateTime loadedAt,
        String loadedBy,
        String reloadReason,
        Map<String, DeployScenarioDefinition> scenarios
) {

    public DeployScenarioCatalog {
        if (catalogVersion <= 0) {
            throw new IllegalArgumentException("catalogVersion must be greater than 0");
        }
        catalogChecksum = requireText("catalogChecksum", catalogChecksum);
        loadedAt = Objects.requireNonNull(loadedAt, "loadedAt is null");
        loadedBy = requireText("loadedBy", loadedBy);
        reloadReason = requireText("reloadReason", reloadReason);
        scenarios = Map.copyOf(Objects.requireNonNull(scenarios, "scenarios is null"));
    }

    public int scenarioCount() {
        return scenarios.size();
    }

    public List<DeployScenarioDefinition> sortedScenarios() {
        return scenarios.values().stream()
                .sorted(Comparator.comparing(definition -> definition.scenario().code()))
                .toList();
    }

    private static String requireText(final String fieldName, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
