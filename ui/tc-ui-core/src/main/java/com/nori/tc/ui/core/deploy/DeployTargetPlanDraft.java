package com.nori.tc.ui.core.deploy;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;

/**
 * planner 결과로 계산된 target 1대 분 plan 초안입니다.
 */
public record DeployTargetPlanDraft(
        String eqpId,
        String interfaceType,
        DeployCurrentState currentState,
        DeployDesiredState desiredState,
        Set<DeployDeltaKind> deltaKinds,
        String resolvedScenarioCode,
        Integer resolvedScenarioVersion,
        List<DeployResolvedStepPlan> resolvedSteps,
        JsonNode desiredStateJson,
        JsonNode deltaJson,
        JsonNode resolvedStepPlanJson,
        boolean noChange
) {
}
