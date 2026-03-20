package com.nori.tc.ui.core.deploy;

/**
 * deploy target 상태입니다.
 */
public enum DeployTargetStatus {
    PENDING,
    NO_CHANGE,
    RUNNING,
    COMPLETED,
    FAILED,
    ROLLING_BACK,
    ROLLED_BACK,
    COMPENSATION_FAILED,
    CANCELED
}
