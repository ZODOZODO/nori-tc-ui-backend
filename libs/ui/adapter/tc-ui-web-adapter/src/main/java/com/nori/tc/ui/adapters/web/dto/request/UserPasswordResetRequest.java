package com.nori.tc.ui.adapters.web.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 사용자 비밀번호 초기화 요청 DTO입니다.
 *
 * <p>대상 엔드포인트:</p>
 * <ul>
 *   <li>POST /api/user/{userPk}/password/reset</li>
 * </ul>
 *
 * @param newPassword 초기화할 새 비밀번호 원문(필수)
 * @param updatedBy 수정자 식별자(선택)
 */
public record UserPasswordResetRequest(

        @NotBlank(message = "newPassword는 필수입니다.")
        String newPassword,

        String updatedBy
) {
}

