package com.nori.tc.ui.adapters.web.dto.request;

/**
 * POST /api/eqp/{eqpId}/checkout 요청 본문 DTO입니다.
 *
 * @param sourceVersion 복사 기준 파라미터 버전.
 *                      null 또는 빈 문자열이면 파라미터 없는 빈 EDIT 버전으로 체크아웃합니다.
 */
public record EqpCheckoutRequest(
        String sourceVersion
) {
}
