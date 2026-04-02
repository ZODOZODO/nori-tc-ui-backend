package com.nori.tc.ui.core.deploy;

import com.nori.tc.ui.core.model.UiCommandEventType;

import java.util.List;
import java.util.Objects;

/**
 * start/resume 시점에 실행할 deploy payload 입니다.
 */
public record DeployExecutionPlan(
        long deployKey,
        UiCommandEventType eventType,
        List<DeployExecutionTarget> targets
) {

    public DeployExecutionPlan {
        if (deployKey <= 0L) {
            throw new IllegalArgumentException("deployKey must be greater than 0");
        }
        eventType = Objects.requireNonNull(eventType, "eventType is null");
        targets = List.copyOf(Objects.requireNonNull(targets, "targets is null"));
    }
}
