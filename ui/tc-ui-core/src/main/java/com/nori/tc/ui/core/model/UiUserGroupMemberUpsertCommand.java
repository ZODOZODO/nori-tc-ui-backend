package com.nori.tc.ui.core.model;

import java.time.OffsetDateTime;

/**
 * 사용자-그룹 매핑 저장용 UI Core 명령입니다.
 */
public record UiUserGroupMemberUpsertCommand(
        Long ugmKey,
        long userPk,
        long groupId,
        OffsetDateTime grantedAt,
        String grantedBy
) {
}
