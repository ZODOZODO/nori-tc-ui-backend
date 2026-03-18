package com.nori.tc.ui.adapters.web.dto.response;

import com.nori.tc.db.domain.common.user.UserStatus;

import java.time.OffsetDateTime;

/**
 * 사용자 조회 응답 DTO입니다.
 *
 * <p>보안 정책상 {@code passwordHash}는 절대 노출하지 않습니다.</p>
 *
 * @param userPk 사용자 PK
 * @param company 회사명
 * @param department 부서명
 * @param userName 사용자 이름
 * @param userId 로그인 ID 원문
 * @param userIdNorm 로그인 ID 정규화 값
 * @param email 이메일
 * @param status 사용자 상태
 * @param createdAt 생성 시각
 * @param updatedAt 수정 시각
 * @param createdBy 생성자
 * @param updatedBy 수정자
 */
public record UserInfoResponse(
        Long userPk,
        String company,
        String department,
        String userName,
        String userId,
        String userIdNorm,
        String email,
        UserStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {
}

