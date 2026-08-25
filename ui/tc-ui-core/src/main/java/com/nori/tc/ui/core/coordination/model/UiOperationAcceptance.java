package com.nori.tc.ui.core.coordination.model;

import java.util.Objects;

/**
 * HTTP mutation 접수 결과입니다(§13.2).
 *
 * @param operationId 접수된 operation 식별자
 * @param state 현재 terminal 여부(신규 접수는 {@link UiOperationState#PENDING})
 * @param phase 현재 phase
 * @param created 이번 요청이 operation 을 새로 만들었는지 여부(false = 같은 key+digest 재요청)
 * @param errorCode terminal 재요청이면 원 결과의 error code
 * @param errorMsg terminal 재요청이면 원 결과의 error message
 */
public record UiOperationAcceptance(
        String operationId,
        UiOperationState state,
        UiOperationPhase phase,
        boolean created,
        String errorCode,
        String errorMsg
) {

    /**
     * 필수 값을 검증합니다.
     */
    public UiOperationAcceptance {
        Objects.requireNonNull(operationId, "operationId is null");
        Objects.requireNonNull(state, "state is null");
        Objects.requireNonNull(phase, "phase is null");
    }

    /**
     * 신규 접수 결과를 만듭니다.
     *
     * @param operationId operation 식별자
     * @return ACCEPTED/PENDING 접수 결과
     */
    public static UiOperationAcceptance accepted(final String operationId) {
        return new UiOperationAcceptance(
                operationId, UiOperationState.PENDING, UiOperationPhase.ACCEPTED, true, null, null);
    }
}
