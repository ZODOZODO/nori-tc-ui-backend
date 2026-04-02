package com.nori.tc.ui.core.eqp;

/**
 * direct EQP 비동기 accept 결과입니다.
 */
public record EqpAsyncAcceptResult(
        boolean accepted,
        int statusCode,
        String traceId,
        String errorCode,
        String errorMessage
) {

    public static EqpAsyncAcceptResult accepted(final String traceId) {
        return new EqpAsyncAcceptResult(true, 202, traceId, null, null);
    }

    public static EqpAsyncAcceptResult error(
            final int statusCode,
            final String errorCode,
            final String errorMessage
    ) {
        return new EqpAsyncAcceptResult(false, statusCode, null, errorCode, errorMessage);
    }
}
