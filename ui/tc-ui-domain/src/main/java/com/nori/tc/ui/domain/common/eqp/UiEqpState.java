package com.nori.tc.ui.domain.common.eqp;

/**
 * UI Backend 내부에서 사용하는 설비 상태입니다.
 */
public enum UiEqpState {
    IDLE,
    RUN,
    DOWN,
    MAINTENANCE,
    PAUSE,
    SERVICE_UNAVAILABLE
}
