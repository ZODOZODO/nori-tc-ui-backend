package com.nori.tc.ui.adapters.web.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/model/{modelVersionKey}/checkin 요청 본문 DTO입니다.
 *
 * @param newVersion 체크인으로 생성할 새 버전 문자열
 * @param description 체크인 결과 모델 설명
 */
public record ModelCheckinRequest(

        @NotBlank(message = "newVersion은 필수입니다.")
        String newVersion,

        String description
) {
}
