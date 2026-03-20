package com.nori.tc.ui.core.deploy;

/**
 * deploy 헤더 상태입니다.
 */
public enum DeployStatus {
    PLANNING,
    READY,
    RUNNING,
    COMPLETED,
    PARTIAL_FAILED,
    FAILED,
    CANCELED
}
