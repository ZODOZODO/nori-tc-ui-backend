package com.nori.tc.ui.adapters.web.dto.response;

/**
 * 그룹 멤버 GET 응답 DTO입니다.
 *
 * <p>{@code GET /api/group/{groupId}/user} 응답에 사용됩니다.</p>
 *
 * @param userPk 사용자 PK
 * @param userId 사용자 ID
 * @param userName 사용자 이름
 * @param company 회사
 * @param department 부서
 */
public record GroupMemberResponse(
        long userPk,
        String userId,
        String userName,
        String company,
        String department
) {
}
