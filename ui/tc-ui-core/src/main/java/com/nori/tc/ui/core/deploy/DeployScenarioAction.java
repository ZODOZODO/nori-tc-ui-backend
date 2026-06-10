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
    /**
     * EQP_UPDATE_PARTITION_MOVE 시나리오에서 옛 owner Gateway 인스턴스에게 routePartition 이동을 통보하는 step.
     * UI 가 옛 routePartition 으로 EQP_UPDATE 를 명시 발행하므로 옛 owner pod 만 수신해 listener 해제 / context 정리.
     * payload 는 일반 UPSERT_GATEWAY_CONTEXT 와 동일하되 publish target partition 이 OLD 입니다.
     */
    UPSERT_GATEWAY_CONTEXT_OLD_PARTITION,
    /**
     * EQP_UPDATE_PARTITION_MOVE 시나리오에서 새 owner Gateway 인스턴스에게 routePartition 이동을 통보하는 step.
     * UI 가 새 routePartition 으로 EQP_UPDATE 를 명시 발행하므로 새 owner pod 만 수신해 listener bind / context 등록.
     */
    UPSERT_GATEWAY_CONTEXT_NEW_PARTITION,
    RECONNECT_GATEWAY,
    REBUILD_GATEWAY_RUNTIME,
    RELOAD_GATEWAY_PLUGIN,
    END_EQP_IF_RUNNING,
    DELETE_GATEWAY_MEMORY,
    DELETE_BUSINESS_BINDING,
    SEND_MESSAGE,
    /**
     * PLC 폴링 그룹 1건의 주기 폴링 타이머를 gateway 에서 시작하는 step(EQP_PLC_GROUP_START).
     * groupMessageName 은 uiMessage 에 실려 전달되며, gateway 는 IMMEDIATE reply 만 발행한다(DB write 0).
     */
    START_PLC_POLL_GROUP,
    /**
     * PLC 폴링 그룹 1건의 주기 폴링 타이머를 gateway 에서 중단하는 step(EQP_PLC_GROUP_END).
     */
    END_PLC_POLL_GROUP,
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
