package com.nori.tc.ui.adapters.web.dto.response;

import java.util.List;

/**
 * Register deploy 시나리오 preview 응답입니다.
 */
public record DeployRegisterScenarioPreviewResponse(
        List<String> removedEqpIds,
        List<String> remainingEqpIds,
        List<String> unionChangeKinds,
        String recommendedScenarioCode,
        String recommendedReason,
        List<ScenarioCandidate> scenarios
) {

    /**
     * 선택 가능한 시나리오 후보입니다.
     */
    public record ScenarioCandidate(
            String code,
            String name,
            String summary,
            int version,
            int stepCount,
            List<String> coversChangeKinds,
            boolean recommended,
            boolean selectable,
            String reason
    ) {
    }
}
