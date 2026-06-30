package com.nori.tc.ui.core.model;

import com.nori.tc.ui.domain.auth.UiUserStatus;

import java.time.OffsetDateTime;

/**
 * 사용자 저장/수정용 UI Core 명령입니다.
 *
 * <p>{@code passwordChangedAt} 은 비밀번호가 신규 설정/변경되는 upsert 에서만 now 로 채우고,
 * 비밀번호 미변경 upsert 에서는 null 로 두어 DB 의 기존 값(만료 카운트다운 기준)을 보존한다.</p>
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
        OffsetDateTime passwordChangedAt,
        String createdBy,
        String updatedBy
) {
}
