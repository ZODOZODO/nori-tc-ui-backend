package com.nori.tc.ui.adapters.web.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nori.tc.ui.adapters.web.dto.request.DeployCreateRequest;
import com.nori.tc.ui.adapters.web.dto.request.DeployRegisterScenarioPreviewRequest;
import com.nori.tc.ui.core.deploy.DeployExecutionPolicy;
import com.nori.tc.ui.core.deploy.DeployPlanCommand;
import com.nori.tc.ui.core.deploy.DeployRegisterScenarioPreviewCommand;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.model.UiCommandEventType;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;

final class DeployControllerRequestMapper {

    private final ObjectMapper objectMapper;

    DeployControllerRequestMapper(final ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
    }

    DeployPlanCommand toCommand(final DeployCreateRequest request) {
        try {
            return new DeployPlanCommand(
                    request.deployName(),
                    UiCommandEventType.fromText(request.eventType()),
                    DeployExecutionPolicy.fromText(request.executionPolicy()),
                    new DeployPlanCommand.CommonRequest(
                            request.commonRequest().interfaceType(),
                            toJsonObject(request.commonRequest().uiMessage(), "commonRequest.uiMessage"),
                            request.commonRequest().resolvedScenarioCode()
                    ),
                    request.targets().stream()
                            .map(target -> new DeployPlanCommand.TargetRequest(
                                    target.eqpId(),
                                    toJsonObject(target.uiMessageOverride(), "targets[].uiMessageOverride")
                            ))
                            .toList()
            );
        } catch (IllegalArgumentException exception) {
            throw new UiBadRequestException(exception.getMessage());
        }
    }

    DeployRegisterScenarioPreviewCommand toPreviewCommand(
            final DeployRegisterScenarioPreviewRequest request
    ) {
        try {
            return new DeployRegisterScenarioPreviewCommand(
                    request.commonRequest().interfaceType(),
                    toJsonObject(request.commonRequest().uiMessage(), "commonRequest.uiMessage"),
                    request.targets().stream()
                            .map(target -> new DeployRegisterScenarioPreviewCommand.TargetRequest(
                                    target.eqpId(),
                                    toJsonObject(target.uiMessageOverride(), "targets[].uiMessageOverride")
                            ))
                            .toList()
            );
        } catch (IllegalArgumentException exception) {
            throw new UiBadRequestException(exception.getMessage());
        }
    }

    LocalDate parseLocalDate(
            final String rawDate,
            final String fieldName
    ) {
        if (rawDate == null || rawDate.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(rawDate.trim());
        } catch (DateTimeParseException exception) {
            throw new UiBadRequestException(fieldName + "는 yyyy-MM-dd 형식이어야 합니다.");
        }
    }

    UiCommandEventType parseEventType(final String rawEventType) {
        if (rawEventType == null || rawEventType.isBlank()) {
            return null;
        }

        try {
            return UiCommandEventType.fromText(rawEventType);
        } catch (IllegalArgumentException exception) {
            throw new UiBadRequestException(exception.getMessage());
        }
    }

    private JsonNode toJsonObject(
            final Object value,
            final String fieldName
    ) {
        if (value == null) {
            return null;
        }
        final JsonNode jsonNode = objectMapper.valueToTree(value);
        if (!jsonNode.isObject()) {
            throw new UiBadRequestException(fieldName + " 는 JSON object 여야 합니다.");
        }
        return jsonNode;
    }
}
