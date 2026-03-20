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
    NO_CHANGE
}
