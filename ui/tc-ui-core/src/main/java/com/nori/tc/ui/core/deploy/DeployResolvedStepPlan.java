package com.nori.tc.ui.core.deploy;

/**
 * target 별로 실제 실행할 step plan 입니다.
 */
public record DeployResolvedStepPlan(
        int order,
        String code,
        String name,
        DeployTargetApp targetApp,
        String description,
        DeployScenarioAction action,
        long timeoutMs,
        boolean replyRequired,
        DeployScenarioRetryPolicy retry,
        boolean rollbackEnabled,
        DeployRollbackMode rollbackMode,
        DeployScenarioAction rollbackAction
) {
}
