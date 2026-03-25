package com.nori.tc.ui.core.model;

import java.time.OffsetDateTime;

/**
 * 그룹-권한 매핑 저장용 UI Core 명령입니다.
 */
public record UiUserGroupPermissionUpsertCommand(
        long groupId,
        long permId,
        OffsetDateTime grantedAt,
        String grantedBy
) {
}
