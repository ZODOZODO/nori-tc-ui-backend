package com.nori.tc.ui.adapters.web.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/auth/login 요청 본문 DTO입니다.
 *
 * @param userId   로그인 사용자 ID (공백 불허)
 * @param password 원문 비밀번호 (공백 불허)
 */
public record LoginRequest(

        @NotBlank(message = "userId는 필수입니다.")
        String userId,

        @NotBlank(message = "password는 필수입니다.")
        String password
) {
}
