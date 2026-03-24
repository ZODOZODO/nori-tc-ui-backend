package com.nori.tc.ui.domain.useradmin;

import com.nori.tc.ui.domain.common.user.UiPermissionMatchType;
import com.nori.tc.ui.domain.common.user.UiPermissionResourceType;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 권한 스냅샷입니다.
 */
public record UiPermission(
        long permId,
        String permCode,
        String permName,
        UiPermissionResourceType resourceType,
        UiPermissionMatchType matchType,
        String resource,
        String httpMethod,
        String description,
        boolean isActive,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {
}
