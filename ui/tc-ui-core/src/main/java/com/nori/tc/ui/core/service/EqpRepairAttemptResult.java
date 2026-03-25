package com.nori.tc.ui.core.service;

/**
 * 보상 시도 결과를 표현합니다.
 */
record EqpRepairAttemptResult(
        boolean success,
        String errorCode,
        String errorMessage
) {

    static EqpRepairAttemptResult successResult() {
        return new EqpRepairAttemptResult(true, null, null);
    }

    static EqpRepairAttemptResult failureResult(
            final String errorCode,
            final String errorMessage
    ) {
        return new EqpRepairAttemptResult(false, errorCode, errorMessage);
    }
}
