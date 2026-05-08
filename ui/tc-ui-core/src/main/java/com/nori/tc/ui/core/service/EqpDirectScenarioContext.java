package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import com.nori.tc.ui.core.deploy.DeployTargetPlanDraft;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.model.UiCommandEventType;

/**
 * direct EQP scenario 실행 시 공유하는 단일 컨텍스트입니다.
 *
 * <p>{@code pendingUpdateCommand} 는 EQP_UPDATE 시나리오의 publish-first 패턴을 위해 사용됩니다.
 * prepareUpdate 단계에서는 DB 를 변경하지 않고 본 필드에 명령을 보관해두며,
 * 모든 step PASS 후 finalizeSuccess 에서 1회만 persistenceService.update 가 호출됩니다.
 * UPDATE 외 이벤트에는 {@code null} 입니다.</p>
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
        JsonNode targetPreState,
        EqpManagementCommand.Update pendingUpdateCommand
) {
}
