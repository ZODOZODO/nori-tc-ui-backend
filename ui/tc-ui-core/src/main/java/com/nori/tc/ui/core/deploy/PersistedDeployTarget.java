package com.nori.tc.ui.core.deploy;

/**
 * deploy target plan 저장 결과입니다.
 */
public record PersistedDeployTarget(
        long deployTargetKey,
        int targetOrder,
        DeployTargetPlanDraft planDraft
) {
}
