package com.nori.tc.ui.core.eqp;

/**
 * 비동기 EQP 명령(EQP_START / EQP_END)의 polling 종료 결과입니다.
 *
 * <p>{@code EqpManagementService.awaitAsyncResult} 가 traceId 의 최종 상태를 PASS/FAIL/TIMEOUT 으로 환원해 반환합니다.
 * gateway 응답이 PASS 면 {@link State#PASS}, FAIL reply 면 {@link State#FAIL}(errorCode/errorMessage 운반),
 * 지정 시간 내 종료 상태에 도달하지 못하면 {@link State#TIMEOUT} 입니다.</p>
 */
public record StartOutcome(State state, String errorCode, String errorMessage) {

    public enum State {
        PASS,
        FAIL,
        TIMEOUT
    }

    public static StartOutcome pass() {
        return new StartOutcome(State.PASS, null, null);
    }

    public static StartOutcome fail(final String errorCode, final String errorMessage) {
        return new StartOutcome(State.FAIL, errorCode, errorMessage);
    }

    public static StartOutcome timeout() {
        return new StartOutcome(State.TIMEOUT, null, null);
    }

    public boolean isPass() {
        return state == State.PASS;
    }
}
