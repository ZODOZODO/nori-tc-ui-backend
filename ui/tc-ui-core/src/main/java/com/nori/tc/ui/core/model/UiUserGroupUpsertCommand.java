package com.nori.tc.ui.core.model;

/**
 * 사용자 그룹 저장/수정용 UI Core 명령입니다.
 */
public record UiUserGroupUpsertCommand(
        Long groupId,
        String groupCode,
        String groupName,
        String description,
        boolean isActive
) {
}
