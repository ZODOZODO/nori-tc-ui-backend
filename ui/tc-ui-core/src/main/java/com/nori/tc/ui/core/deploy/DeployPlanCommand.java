package com.nori.tc.ui.core.deploy;

import tools.jackson.databind.JsonNode;
import com.nori.tc.ui.core.model.UiCommandEventType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * T3 planner/T4 deploy API가 공통으로 사용하는 입력 모델입니다.
 */
public record DeployPlanCommand(
        String deployName,
        UiCommandEventType eventType,
        DeployExecutionPolicy executionPolicy,
        CommonRequest commonRequest,
        List<TargetRequest> targets
) {

    public DeployPlanCommand {
        deployName = requireText("deployName", deployName);
        eventType = Objects.requireNonNull(eventType, "eventType is null");
        executionPolicy = executionPolicy == null ? DeployExecutionPolicy.BEST_EFFORT : executionPolicy;
        commonRequest = Objects.requireNonNull(commonRequest, "commonRequest is null");
        targets = List.copyOf(Objects.requireNonNull(targets, "targets is null"));
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("targets must not be empty");
        }

        final Set<String> normalizedEqpIds = new LinkedHashSet<>();
        for (TargetRequest target : targets) {
            if (!normalizedEqpIds.add(target.eqpId().trim())) {
                throw new IllegalArgumentException("duplicated target eqpId: " + target.eqpId());
            }
        }
    }

    public record CommonRequest(
            String interfaceType,
            JsonNode uiMessage
    ) {
        public CommonRequest {
            interfaceType = requireText("commonRequest.interfaceType", interfaceType);
            if (uiMessage == null || !uiMessage.isObject()) {
                throw new IllegalArgumentException("commonRequest.uiMessage must be a JSON object");
            }
        }
    }

    public record TargetRequest(
            String eqpId,
            JsonNode uiMessageOverride
    ) {
        public TargetRequest {
            eqpId = requireText("targets[].eqpId", eqpId);
            if (uiMessageOverride != null && !uiMessageOverride.isObject()) {
                throw new IllegalArgumentException("targets[].uiMessageOverride must be a JSON object");
            }
        }
    }

    private static String requireText(final String fieldName, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
