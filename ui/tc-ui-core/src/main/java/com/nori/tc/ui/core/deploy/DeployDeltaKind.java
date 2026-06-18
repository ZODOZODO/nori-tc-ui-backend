package com.nori.tc.ui.core.deploy;

/**
 * Deploy planner가 비교 결과로 산출하는 표준 delta 종류입니다.
 */
public enum DeployDeltaKind {
    MODEL_CHANGED,
    BUSINESS_JAR_CHANGED,
    GATEWAY_JAR_CHANGED,
    EQP_PARAM_CHANGED,
    CONNECTION_ENDPOINT_CHANGED,
    GATEWAY_RUNTIME_SHAPE_CHANGED,
    /**
     * EQP의 routePartition이 변경된 경우입니다.
     * routePartition은 Business runtime cache(명령 발행 시 partition 결정)와
     * Gateway equipment context(expectedRoutePartition / PASSIVE listener owned 판정 / shard ownership)
     * 양쪽 in-memory state에 동시에 영향을 주므로, 변경 시 반드시 Business + Gateway 양쪽 cache를 모두 갱신하는
     * step을 태워야 합니다. 어느 한쪽이라도 stale이 되면 Gateway가 ROUTE_PARTITION_MISMATCH로 명령을 reject 하게 됩니다.
     */
    ROUTE_PARTITION_CHANGED,
    /**
     * PLC 설비의 모델(디코드 스펙) 변경입니다. MODEL_CHANGED 와 함께 PLC 설비에서만 산출됩니다.
     * register-model-* 시나리오의 {@code targetApp: GATEWAY} 스텝을 PLC 한정으로 게이팅해, PLC 디코드 스펙(groups)을
     * 담은 프로파일을 게이트웨이로 전파합니다(SECS/SOCKET 은 이 delta 가 없어 게이트웨이 스텝 미생성).
     */
    PLC_MODEL_CHANGED,
    NO_CHANGE
}
