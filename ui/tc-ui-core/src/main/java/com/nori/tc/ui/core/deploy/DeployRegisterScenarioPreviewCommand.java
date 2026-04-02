package com.nori.tc.ui.core.deploy;

import tools.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Register deploy 시나리오 preview 입력입니다.
 */
public record DeployRegisterScenarioPreviewCommand(
        String interfaceType,
        JsonNode uiMessage,
        List<TargetRequest> targets
) {

    public DeployRegisterScenarioPreviewCommand {
        interfaceType = requireText("interfaceType", interfaceType);
        if (uiMessage == null || !uiMessage.isObject()) {
            throw new IllegalArgumentException("uiMessage must be a JSON object");
        }
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
