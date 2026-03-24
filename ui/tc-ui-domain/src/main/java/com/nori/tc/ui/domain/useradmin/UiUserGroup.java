package com.nori.tc.ui.domain.useradmin;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 사용자 그룹 스냅샷입니다.
 */
public record UiUserGroup(
        long groupId,
        String groupCode,
        String groupName,
        String description,
        boolean isActive,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
