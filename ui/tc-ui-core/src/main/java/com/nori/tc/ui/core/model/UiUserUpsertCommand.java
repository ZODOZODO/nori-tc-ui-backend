package com.nori.tc.ui.core.model;

import com.nori.tc.ui.domain.auth.UiUserStatus;

/**
 * 사용자 저장/수정용 UI Core 명령입니다.
 */
public record UiUserUpsertCommand(
        Long userPk,
        String company,
        String department,
        String userName,
        String userId,
        String userIdNorm,
        String passwordHash,
        String email,
        UiUserStatus status,
        String createdBy,
        String updatedBy
) {
}
