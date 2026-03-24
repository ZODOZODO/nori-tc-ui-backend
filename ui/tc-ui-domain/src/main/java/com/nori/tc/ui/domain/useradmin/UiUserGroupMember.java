package com.nori.tc.ui.domain.useradmin;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 사용자-그룹 매핑 스냅샷입니다.
 */
public record UiUserGroupMember(
        long ugmKey,
        long userPk,
        long groupId,
        OffsetDateTime grantedAt,
        String grantedBy
) {
}
