package com.nori.tc.ui.core.eqp;

/**
 * EQP 관리 명령 최종 처리 결과입니다.
 */
public record EqpCommandResult(
        boolean success,
        int statusCode,
        String errorCode,
        String errorMessage
) {

    /**
     * 성공 결과를 생성합니다.
     *
     * @return 200 성공 결과
     */
    public static EqpCommandResult ok() {
        return new EqpCommandResult(true, 200, null, null);
    }

    /**
     * 실패 결과를 생성합니다.
     *
     * @param statusCode HTTP 상태 코드
     * @param errorCode 오류 코드
     * @param errorMessage 오류 메시지
     * @return 실패 결과
     */
    public static EqpCommandResult error(
            final int statusCode,
            final String errorCode,
            final String errorMessage
    ) {
        return new EqpCommandResult(false, statusCode, errorCode, errorMessage);
    }
}
