package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import com.nori.tc.ui.core.deploy.DeployTargetPlanDraft;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.model.UiCommandEventType;

/**
 * direct EQP scenario 실행 시 공유하는 단일 컨텍스트입니다.
 */
public record EqpDirectScenarioContext(
        String executionId,
        UiCommandEventType eventType,
        String eqpId,
        String interfaceType,
        String actor,
        JsonNode uiMessage,
        DeployTargetPlanDraft planDraft,
        PersistedDeployTarget syntheticTarget,
        EqpManagementSnapshot previousSnapshot,
        EqpManagementSnapshot executionSnapshot,
        JsonNode targetPreState
) {
}
