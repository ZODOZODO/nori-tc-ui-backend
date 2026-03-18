package com.nori.tc.ui.adapters.web.dto.response;

import java.time.OffsetDateTime;

/**
 * 사용자-그룹 매핑 응답 DTO입니다.
 *
 * @param ugmKey 매핑 PK
 * @param userPk 사용자 PK
 * @param groupId 그룹 PK
 * @param grantedAt 부여 시각
 * @param grantedBy 부여자
 */
public record UserGroupMappingResponse(
        long ugmKey,
        long userPk,
        long groupId,
        OffsetDateTime grantedAt,
        String grantedBy
) {
}

