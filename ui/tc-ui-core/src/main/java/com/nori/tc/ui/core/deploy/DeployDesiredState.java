package com.nori.tc.ui.core.deploy;

import tools.jackson.databind.JsonNode;

/**
 * planner가 eventType별 uiMessage를 해석해 얻은 목표 상태입니다.
 */
public record DeployDesiredState(
        String interfaceType,
        Long modelVersionKey,
        Long businessJarRef,
        Long gatewayJarRef,
        String eqpParamVersion,
        String connectionMode,
        String eqpIp,
        Integer eqpPort,
        String socketType,
        Integer hsmsDeviceId,
        JsonNode mergedUiMessage
) {
}
