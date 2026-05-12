package com.nori.tc.ui.adapters.web.dto.response;

/**
 * 사용자-그룹 매핑 GET 응답 DTO입니다.
 *
 * <p>{@code GET /api/user/{userPk}/group} 응답에 사용됩니다.
 * ui-front {@code MockUserGroupRow} 의 필드 집합과 1:1 일치합니다.</p>
 *
 * @param userPk 사용자 PK
 * @param groupId 그룹 PK
 * @param groupCode 그룹 코드
 * @param groupName 그룹 이름
 */
public record UserGroupMembershipResponse(
        long userPk,
        long groupId,
        String groupCode,
        String groupName
) {
}
