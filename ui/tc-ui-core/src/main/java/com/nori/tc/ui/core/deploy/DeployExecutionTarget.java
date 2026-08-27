package com.nori.tc.ui.core.deploy;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * start/resume 시점 target 실행 상태와 plan 을 함께 전달합니다.
 *
 * @param currentStepTimeoutAt 현재 step 의 응답 deadline(발행 시 영속화된 값). 인계 Pod 가 로컬 타이머
 *                             없이 같은 판정을 하려면 deadline 이 영속 상태에서 와야 합니다.
 */
public record DeployExecutionTarget(
        PersistedDeployTarget target,
        DeployTargetStatus targetStatus,
        Integer currentStepOrder,
        String currentStepCode,
        DeployStepStatus currentStepStatus,
        OffsetDateTime currentStepTimeoutAt
) {

    public DeployExecutionTarget {
        target = Objects.requireNonNull(target, "target is null");
        targetStatus = Objects.requireNonNull(targetStatus, "targetStatus is null");
    }
}
