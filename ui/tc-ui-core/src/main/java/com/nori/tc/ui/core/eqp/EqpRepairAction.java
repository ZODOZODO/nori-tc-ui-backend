package com.nori.tc.ui.core.eqp;

/**
 * EQP 보상 실패 후 운영자가 재시도해야 하는 복구 액션 유형입니다.
 */
public enum EqpRepairAction {
    DB_DELETE,
    DB_RESTORE,
    RUNTIME_CREATE,
    RUNTIME_UPDATE,
    RUNTIME_DELETE,
    JAR_RELOAD
}
