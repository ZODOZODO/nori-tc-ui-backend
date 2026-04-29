package com.nori.tc.ui.core.deploy;

/**
 * scenario step 실행/보상 action enum 입니다.
 */
public enum DeployScenarioAction {
    START_GATEWAY_RUNTIME,
    END_GATEWAY_RUNTIME,
    UPDATE_MODEL_BINDING,
    RELOAD_BUSINESS_MODEL_RUNTIME,
    RELOAD_BUSINESS_PLUGIN,
    /**
     * Business runtime cache의 EQP 메타데이터(routePartition 등)만 갱신하는 step.
     * EQP_UPDATE에서 routePartition이 바뀐 경우 호출되며, Business는 EQP_UPDATE 이벤트로 전달받아
     * BusinessModelRuntimeCache를 새 snapshot으로 CAS 교체합니다.
     */
    UPDATE_BUSINESS_RUNTIME_METADATA,
    /**
     * Gateway equipment context의 routePartition / 라우팅 메타데이터를 갱신하는 step.
     * EQP_UPDATE에서 routePartition이 바뀐 경우 호출되며, Gateway는 EQP_UPDATE 이벤트로 전달받아
     * GatewayEquipmentInfo를 DB에서 다시 적재합니다(GatewayUiTaskProcessorRegistry → createOrUpdateContext).
     * 이 step이 빠지면 Gateway expectedRoutePartition / PASSIVE listener owned 판정이 stale 상태로 남아
     * Business가 새 partition으로 보낸 명령이 ROUTE_PARTITION_MISMATCH로 reject 됩니다.
     */
    UPDATE_GATEWAY_RUNTIME_METADATA,
    UPDATE_EQP_PARAM,
    UPSERT_GATEWAY_CONTEXT,
    RECONNECT_GATEWAY,
    REBUILD_GATEWAY_RUNTIME,
    RELOAD_GATEWAY_PLUGIN,
    END_EQP_IF_RUNNING,
    DELETE_GATEWAY_MEMORY,
    DELETE_BUSINESS_BINDING,
    SEND_MESSAGE,
    RESTORE_PREVIOUS_MODEL_BINDING,
    RESTORE_PREVIOUS_BUSINESS_RUNTIME,
    RESTORE_PREVIOUS_BUSINESS_PLUGIN,
    RESTORE_PREVIOUS_EQP_PARAM,
    RESTORE_PREVIOUS_GATEWAY_CONTEXT,
    RESTORE_PREVIOUS_GATEWAY_RUNTIME,
    RESTORE_PREVIOUS_GATEWAY_PLUGIN,
    RESTORE_PREVIOUS_BUSINESS_BINDING,
    /**
     * UPDATE_BUSINESS_RUNTIME_METADATA의 보상 action. Business runtime cache를 이전 snapshot 값으로 되돌립니다.
     */
    RESTORE_PREVIOUS_BUSINESS_RUNTIME_METADATA,
    /**
     * UPDATE_GATEWAY_RUNTIME_METADATA의 보상 action. Gateway equipment context를 이전 snapshot 값으로 되돌립니다.
     */
    RESTORE_PREVIOUS_GATEWAY_RUNTIME_METADATA
}
