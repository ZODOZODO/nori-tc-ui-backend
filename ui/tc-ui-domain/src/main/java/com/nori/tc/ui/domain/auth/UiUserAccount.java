package com.nori.tc.ui.domain.auth;

import java.util.Objects;

/**
 * 인증/세션 코어에서 사용하는 내부 사용자 계정 모델입니다.
 *
 * <p>로그인/토큰 검증에 필요한 최소 필드만 유지하여 DB 레코드 구조 누수를 줄입니다.</p>
 *
 * @param userPk 사용자 PK
 * @param userId 로그인 ID
 * @param userIdNorm 정규화 로그인 ID
 * @param passwordHash BCrypt 해시
 * @param status 사용자 상태
 */
public record UiUserAccount(
        long userPk,
        String userId,
        String userIdNorm,
        String passwordHash,
        UiUserStatus status
) {

    public UiUserAccount {
        if (userPk <= 0L) {
            throw new IllegalArgumentException("userPk는 양수여야 합니다. userPk=" + userPk);
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId는 필수입니다.");
        }
        if (userIdNorm == null || userIdNorm.isBlank()) {
            throw new IllegalArgumentException("userIdNorm은 필수입니다.");
        }
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("passwordHash는 필수입니다.");
        }
        Objects.requireNonNull(status, "status는 null일 수 없습니다.");
    }
}
