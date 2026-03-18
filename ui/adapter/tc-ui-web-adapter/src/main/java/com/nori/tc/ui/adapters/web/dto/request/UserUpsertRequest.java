package com.nori.tc.ui.adapters.web.dto.request;

import com.nori.tc.db.domain.common.user.UserStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 사용자 정보 등록/수정 요청 DTO입니다.
 *
 * <p>대상 엔드포인트:</p>
 * <ul>
 *   <li>POST /api/user</li>
 *   <li>PUT /api/user/{userPk}</li>
 * </ul>
 *
 * <p>비밀번호 정책:</p>
 * <ul>
 *   <li>POST: password 필수</li>
 *   <li>PUT: password 생략 시 기존 해시를 유지</li>
 * </ul>
 *
 * @param company 회사명(필수)
 * @param department 부서명(필수)
 * @param userName 사용자 이름(필수)
 * @param userId 로그인 ID 원문(필수)
 * @param password 원문 비밀번호(POST 필수, PUT 선택)
 * @param email 이메일(필수, 형식 검증)
 * @param status 사용자 상태(선택, null이면 정책 기본값 적용)
 * @param createdBy 생성자 식별자(선택)
 * @param updatedBy 수정자 식별자(선택)
 */
public record UserUpsertRequest(

        @NotBlank(message = "company는 필수입니다.")
        String company,

        @NotBlank(message = "department는 필수입니다.")
        String department,

        @NotBlank(message = "userName은 필수입니다.")
        String userName,

        @NotBlank(message = "userId는 필수입니다.")
        String userId,

        String password,

        @NotBlank(message = "email은 필수입니다.")
        @Email(message = "email 형식이 올바르지 않습니다.")
        String email,

        UserStatus status,

        String createdBy,

        String updatedBy
) {
}

