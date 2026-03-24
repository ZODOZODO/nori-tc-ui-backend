package com.nori.tc.ui.domain.useradmin;

import com.nori.tc.ui.domain.auth.UiUserStatus;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 사용자 관리 스냅샷입니다.
 */
public record UiManagedUser(
        Long userPk,
        String company,
        String department,
        String userName,
        String userId,
        String userIdNorm,
        String passwordHash,
        String email,
        UiUserStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {
}
