package com.nori.tc.ui.adapters.kafka.exception;

import java.util.Objects;

/**
 * UI Kafka 발행 실패를 표현하는 런타임 예외입니다.
 *
 * <p>발행 대상(Gateway/Business), traceId, 원인 예외를 함께 보관하여
 * 호출부(EqpController)에서 즉시 실패를 감지하고 적절한 HTTP 오류 응답을 반환할 수 있게 합니다.</p>
 */
public class UiKafkaPublishException extends RuntimeException {

    private final String target;
    private final String traceId;

    /**
     * Kafka 발행 예외를 생성합니다.
     *
     * @param message 예외 메시지
     * @param target 발행 대상 식별자 (예: GATEWAY, BUSINESS)
     * @param traceId 작업 추적 ID
     * @param cause 원인 예외
     */
    public UiKafkaPublishException(
            final String message,
            final String target,
            final String traceId,
            final Throwable cause
    ) {
        super(message, cause);
        this.target = Objects.requireNonNull(target, "target is null");
        this.traceId = Objects.requireNonNull(traceId, "traceId is null");
    }

    public String getTarget() {
        return target;
    }

    public String getTraceId() {
        return traceId;
    }
}
