package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nori.tc.ui.core.deploy.DeployExecutionPolicy;
import com.nori.tc.ui.core.deploy.DeployPlanCommand;
import com.nori.tc.ui.core.deploy.DeployTargetPlanDraft;
import com.nori.tc.ui.core.model.UiCommandEventType;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * direct EQP 이벤트를 single-target scenario plan으로 변환합니다.
 */
@Service
public class EqpDirectScenarioPlanner {

    private final DeployPlanningService deployPlanningService;
    private final ObjectMapper objectMapper;

    public EqpDirectScenarioPlanner(
            final DeployPlanningService deployPlanningService,
            final ObjectMapper objectMapper
    ) {
        this.deployPlanningService = Objects.requireNonNull(deployPlanningService, "deployPlanningService is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
    }

    public DeployTargetPlanDraft plan(
            final UiCommandEventType eventType,
            final String eqpId,
            final String interfaceType,
            final JsonNode uiMessage
    ) {
        return plan(eventType, eqpId, interfaceType, uiMessage, null);
    }

    /**
     * resolvedScenarioCode로 단일 시나리오를 명시적으로 선택하는 plan 변형입니다.
     * 동일 eventType에 대해 분기되는 시나리오(예: EQP_DELETE의 ACTIVE/PASSIVE)를 처리할 때 사용합니다.
     */
    public DeployTargetPlanDraft plan(
            final UiCommandEventType eventType,
            final String eqpId,
            final String interfaceType,
            final JsonNode uiMessage,
            final String resolvedScenarioCode
    ) {
        final DeployPlanCommand command = new DeployPlanCommand(
                "DIRECT-" + eventType.name() + "-" + eqpId,
                eventType,
                DeployExecutionPolicy.BEST_EFFORT,
                new DeployPlanCommand.CommonRequest(
                        interfaceType,
                        uiMessage == null ? objectMapper.createObjectNode() : uiMessage,
                        resolvedScenarioCode
                ),
                List.of(new DeployPlanCommand.TargetRequest(eqpId, objectMapper.createObjectNode()))
        );

        return deployPlanningService.plan(command).targets().getFirst();
    }
}
