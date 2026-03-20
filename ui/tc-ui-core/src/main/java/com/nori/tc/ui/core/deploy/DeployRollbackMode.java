package com.nori.tc.ui.core.deploy;

/**
 * step rollback 가능 여부 분류입니다.
 */
public enum DeployRollbackMode {
    NOT_REQUIRED,
    AUTO_COMPENSATABLE,
    CONDITIONAL_COMPENSATABLE,
    MANUAL_ONLY
}
