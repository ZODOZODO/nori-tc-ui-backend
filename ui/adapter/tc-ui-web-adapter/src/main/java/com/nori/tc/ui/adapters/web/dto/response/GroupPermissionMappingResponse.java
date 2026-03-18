package com.nori.tc.ui.adapters.web.dto.response;

import java.time.OffsetDateTime;

/**
 * 그룹-권한 매핑 응답 DTO입니다.
 *
 * @param ugpKey 매핑 PK
 * @param groupId 그룹 PK
 * @param permId 권한 PK
 * @param grantedAt 부여 시각
 * @param grantedBy 부여자
 */
public record GroupPermissionMappingResponse(
        long ugpKey,
        long groupId,
        long permId,
        OffsetDateTime grantedAt,
        String grantedBy
) {
}

