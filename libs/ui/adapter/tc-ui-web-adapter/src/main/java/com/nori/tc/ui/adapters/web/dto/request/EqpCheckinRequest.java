package com.nori.tc.ui.adapters.web.dto.request;

/**
 * POST /api/eqp/{eqpId}/checkin 요청 본문 DTO입니다.
 *
 * @param description 버전 설명 (선택)
 */
public record EqpCheckinRequest(

        String description
) {
}
