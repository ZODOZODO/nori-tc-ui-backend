package com.nori.tc.ui.adapters.web.dto.response;

/**
 * {@code GET /api/async/{id}} operation 조회 응답 DTO입니다(§13.3).
 *
 * <p>상태는 {@code PENDING} / {@code PASS} / {@code FAIL} / {@code REPAIR_REQUIRED} 뿐이며
 * <b>{@code TIMEOUT} 은 없습니다</b> — 관찰 deadline 이 지나도 operation 은 살아 있고, 그 지연은
 * {@code phase=RECONCILING} 으로만 나타납니다.</p>
 *
 * @param operationId operation 식별자(레거시 경로는 traceId)
 * @param eqpId 대상 설비 ID
 * @param status 처리 상태
 * @param phase 상세 phase
 * @param desiredRevision 발급된 desired revision
 * @param startedAt 접수 시각(epoch ms)
 * @param updatedAt 마지막 전이 시각(epoch ms)
 * @param terminalAt terminal 확정 시각(epoch ms)
 * @param errorCode 오류 코드
 * @param errorMsg 오류 메시지
 * @param repairReason 수동 복구 사유
 */
public record OperationStatusResponse(
        String operationId,
        String eqpId,
        String status,
        String phase,
        Long desiredRevision,
        Long startedAt,
        Long updatedAt,
        Long terminalAt,
        String errorCode,
        String errorMsg,
        String repairReason
) {
}
