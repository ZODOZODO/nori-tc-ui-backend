package com.nori.tc.ui.adapters.web.dto.request;

/**
 * POST /api/model/{modelVersionKey}/checkin 요청 본문 DTO입니다.
 *
 * @param description 체크인 결과 모델 설명
 */
public record ModelCheckinRequest(
        String description
) {
}
