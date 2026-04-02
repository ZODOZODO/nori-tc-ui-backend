package com.nori.tc.ui.core.deploy;

import java.util.Objects;

/**
 * start/resume 시점 target 실행 상태와 plan 을 함께 전달합니다.
 */
public record DeployExecutionTarget(
        PersistedDeployTarget target,
        DeployTargetStatus targetStatus,
        Integer currentStepOrder,
        String currentStepCode,
        DeployStepStatus currentStepStatus
) {

    public DeployExecutionTarget {
        target = Objects.requireNonNull(target, "target is null");
        targetStatus = Objects.requireNonNull(targetStatus, "targetStatus is null");
    }
}
