package com.nori.tc.ui.adapters.web.dto.response;

/**
 * 설비 mutation 접수 응답 DTO입니다(§13.2 — {@code 202 Accepted}).
 *
 * <p>접수는 완료가 아닙니다. Front 는 {@code operationId} 로 {@code GET /api/async/{id}} 를 polling 해
 * 실제 결과를 확인합니다. 접수 시점의 {@code status} 는 {@code PENDING}, {@code phase} 는
 * {@code ACCEPTED} 이며, 같은 {@code Idempotency-Key} 재요청이면 현재/최종 상태가 그대로 실립니다.</p>
 *
 * @param operationId operation 식별자(polling 키)
 * @param status {@code PENDING} / {@code PASS} / {@code FAIL} / {@code REPAIR_REQUIRED}
 * @param phase 상세 phase
 * @param errorCode terminal 재요청이면 원 결과의 오류 코드
 * @param errorMsg terminal 재요청이면 원 결과의 오류 메시지
 */
public record OperationAcceptResponse(
        String operationId,
        String status,
        String phase,
        String errorCode,
        String errorMsg
) {
}
