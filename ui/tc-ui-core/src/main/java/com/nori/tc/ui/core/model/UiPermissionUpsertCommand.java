package com.nori.tc.ui.core.model;

import com.nori.tc.ui.domain.common.user.UiPermissionMatchType;
import com.nori.tc.ui.domain.common.user.UiPermissionResourceType;

/**
 * UI 권한 저장/수정용 UI Core 명령입니다.
 */
public record UiPermissionUpsertCommand(
        Long permId,
        String permCode,
        String permName,
        UiPermissionResourceType resourceType,
        UiPermissionMatchType matchType,
        String resource,
        String httpMethod,
        String description,
        boolean isActive,
        String createdBy,
        String updatedBy
) {
}
