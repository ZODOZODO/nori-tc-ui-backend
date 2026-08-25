package com.nori.tc.ui.core.coordination.model;

import java.util.Objects;

/**
 * operation 실행 권한을 나타내는 executor lease 입니다(§13.4).
 *
 * @param operationId 대상 operation
 * @param sessionId lease 를 보유한 session
 * @param epoch 발급된 executor epoch(단조 증가)
 */
public record UiExecutorLease(String operationId, String sessionId, long epoch) {

    /**
     * 필수 값을 검증합니다.
     */
    public UiExecutorLease {
        Objects.requireNonNull(operationId, "operationId is null");
        Objects.requireNonNull(sessionId, "sessionId is null");
    }
}
