package com.nori.tc.ui.adapters.web.dto.response;

/**
 * GET /api/async/{traceId} polling 응답 DTO입니다.
 *
 * <p>eqp_start / eqp_end 요청 후 Gateway reply가 Redis에 저장된 경우 200으로 반환됩니다.
 * 상태에 따라 202(PENDING) / 200(COMPLETED) / 408(TIMEOUT) / 404(존재하지 않는 traceId)가 반환됩니다.</p>
 *
 * @param traceId       작업 추적 ID
 * @param eqpId         대상 설비 ID
 * @param status        처리 상태 ({@code PENDING}, {@code TIMEOUT}, {@code PASS}, {@code FAIL})
 * @param errorCode     오류 코드 (실패/타임아웃 시 원인 코드, 성공/대기 시 null)
 * @param errorMsg      오류 메시지 (실패/타임아웃 시 상세 설명, 성공/대기 시 null)
 */
public record AsyncResultResponse(
        String traceId,
        String eqpId,
        String status,
        String errorCode,
        String errorMsg
) {
}
