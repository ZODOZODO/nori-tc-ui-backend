package com.nori.tc.ui.core.coordination.model;

import java.util.Objects;

/**
 * operation 조회 응답 모델입니다(§13.3).
 *
 * <p>민감 payload 나 내부 stack trace 는 담지 않습니다.</p>
 *
 * @param operationId operation 식별자(레거시 async 경로는 traceId)
 * @param eqpId 대상 설비
 * @param status {@code PENDING} / {@code PASS} / {@code FAIL} / {@code REPAIR_REQUIRED}
 * @param phase 상세 phase — 관찰 deadline 을 넘긴 비terminal 은 {@code RECONCILING}
 * @param desiredRevision 발급된 desired revision
 * @param startedAtEpochMs 접수 시각
 * @param updatedAtEpochMs 마지막 전이 시각
 * @param terminalAtEpochMs terminal 확정 시각
 * @param errorCode terminal error code
 * @param errorMsg terminal error message
 * @param repairReason 수동 복구 사유
 */
public record UiOperationStatusView(
        String operationId,
        String eqpId,
        UiOperationState status,
        UiOperationPhase phase,
        Long desiredRevision,
        Long startedAtEpochMs,
        Long updatedAtEpochMs,
        Long terminalAtEpochMs,
        String errorCode,
        String errorMsg,
        String repairReason
) {

    /**
     * 필수 값을 검증합니다.
     */
    public UiOperationStatusView {
        Objects.requireNonNull(operationId, "operationId is null");
        Objects.requireNonNull(status, "status is null");
        Objects.requireNonNull(phase, "phase is null");
    }

    /**
     * terminal 여부를 반환합니다.
     *
     * @return terminal 이면 true
     */
    public boolean terminal() {
        return status != UiOperationState.PENDING;
    }
}
