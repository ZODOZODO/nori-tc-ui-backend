package com.nori.tc.ui.core.deploy;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Register deploy 시나리오 preview 결과입니다.
 */
public record DeployRegisterScenarioPreviewResult(
        List<String> removedEqpIds,
        List<String> remainingEqpIds,
        Set<DeployDeltaKind> unionChangeKinds,
        String recommendedScenarioCode,
        String recommendedReason,
        List<ScenarioCandidate> scenarios
) {

    public DeployRegisterScenarioPreviewResult {
        removedEqpIds = List.copyOf(Objects.requireNonNull(removedEqpIds, "removedEqpIds is null"));
        remainingEqpIds = List.copyOf(Objects.requireNonNull(remainingEqpIds, "remainingEqpIds is null"));
        unionChangeKinds = Set.copyOf(Objects.requireNonNull(unionChangeKinds, "unionChangeKinds is null"));
        scenarios = List.copyOf(Objects.requireNonNull(scenarios, "scenarios is null"));
    }

    /**
     * 선택 가능한 시나리오 후보입니다.
     */
    public record ScenarioCandidate(
            String code,
            String name,
            String summary,
            int version,
            int stepCount,
            Set<DeployDeltaKind> coversChangeKinds,
            boolean recommended,
            boolean selectable,
            String reason
    ) {
        public ScenarioCandidate {
            code = requireText("code", code);
            name = requireText("name", name);
            summary = requireText("summary", summary);
            if (version <= 0) {
                throw new IllegalArgumentException("version must be greater than 0");
            }
            if (stepCount < 0) {
                throw new IllegalArgumentException("stepCount must not be negative");
            }
            coversChangeKinds = Set.copyOf(Objects.requireNonNull(coversChangeKinds, "coversChangeKinds is null"));
            reason = requireText("reason", reason);
        }
    }

    private static String requireText(final String fieldName, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
