package com.nori.tc.ui.domain.useradmin;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 그룹-권한 매핑 스냅샷입니다.
 */
public record UiUserGroupPermission(
        long ugpKey,
        long groupId,
        long permId,
        OffsetDateTime grantedAt,
        String grantedBy
) {
}
