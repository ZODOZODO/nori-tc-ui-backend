package com.nori.tc.ui.adapters.web.dto.response;

import com.nori.tc.db.domain.common.user.TcUiPermissionMatchType;
import com.nori.tc.db.domain.common.user.TcUiPermissionResourceType;

import java.time.OffsetDateTime;

/**
 * UI 권한 조회 응답 DTO입니다.
 *
 * @param permId 권한 PK
 * @param permCode 권한 코드
 * @param permName 권한 이름
 * @param resourceType 리소스 타입
 * @param matchType 매칭 타입
 * @param resource 리소스 문자열
 * @param httpMethod HTTP 메서드
 * @param description 설명
 * @param isActive 활성 여부
 * @param createdAt 생성 시각
 * @param updatedAt 수정 시각
 * @param createdBy 생성자
 * @param updatedBy 수정자
 */
public record PermissionInfoResponse(
        long permId,
        String permCode,
        String permName,
        TcUiPermissionResourceType resourceType,
        TcUiPermissionMatchType matchType,
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

