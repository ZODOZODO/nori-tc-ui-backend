package com.nori.tc.ui.adapters.web.dto.response;

/**
 * eqp_start / eqp_end 202 Accepted 즉시 반환 응답 DTO입니다.
 *
 * <p>클라이언트는 반환된 {@code traceId}로 {@code GET /api/async/{traceId}}를
 * polling하여 최종 처리 결과를 확인합니다.</p>
 *
 * @param traceId 작업 추적 ID (Gateway reply가 Redis에 저장될 때 사용되는 키)
 */
public record AsyncAcceptResponse(String traceId) {
}
