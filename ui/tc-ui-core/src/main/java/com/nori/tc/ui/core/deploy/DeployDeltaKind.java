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
    NO_CHANGE
}
