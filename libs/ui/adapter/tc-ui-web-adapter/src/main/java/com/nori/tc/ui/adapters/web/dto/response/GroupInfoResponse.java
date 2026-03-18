package com.nori.tc.ui.adapters.web.dto.response;

import java.time.OffsetDateTime;

/**
 * 그룹 조회 응답 DTO입니다.
 *
 * @param groupId 그룹 PK
 * @param groupCode 그룹 코드
 * @param groupName 그룹 이름
 * @param description 그룹 설명
 * @param isActive 활성 여부
 * @param createdAt 생성 시각
 * @param updatedAt 수정 시각
 */
public record GroupInfoResponse(
        long groupId,
        String groupCode,
        String groupName,
        String description,
        boolean isActive,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

