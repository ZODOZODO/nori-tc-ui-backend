package com.nori.tc.ui.core.deploy;

/**
 * deploy target 내부 step 상태입니다.
 */
public enum DeployStepStatus {
    PENDING,
    SKIPPED,
    RUNNING,
    WAITING_REPLY,
    COMPLETED,
    FAILED,
    ROLLING_BACK,
    ROLLED_BACK,
    COMPENSATION_FAILED,
    CANCELED
}
