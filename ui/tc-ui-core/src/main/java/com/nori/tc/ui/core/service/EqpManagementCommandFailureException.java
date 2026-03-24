package com.nori.tc.ui.core.service;

/**
 * EQP 관리 명령 처리 중 발생한 상태 코드 포함 예외입니다.
 */
final class EqpManagementCommandFailureException extends Exception {

    private final int statusCode;
    private final String errorCode;

    EqpManagementCommandFailureException(
            final int statusCode,
            final String errorCode,
            final String message,
            final Throwable cause
    ) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    int statusCode() {
        return statusCode;
    }

    String errorCode() {
        return errorCode;
    }
}
