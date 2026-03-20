package com.nori.tc.ui.core.deploy;

import com.nori.tc.ui.core.model.UiCommandEventType;

import java.util.List;

/**
 * deploy planner 전체 결과입니다.
 */
public record DeployPlanningResult(
        String deployName,
        UiCommandEventType eventType,
        DeployExecutionPolicy executionPolicy,
        int scenarioCatalogVersion,
        List<DeployTargetPlanDraft> targets
) {
}
