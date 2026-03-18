package com.nori.tc.ui.adapters.web.dto.request;

import com.nori.tc.db.domain.common.user.TcUiPermissionMatchType;
import com.nori.tc.db.domain.common.user.TcUiPermissionResourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * UI 권한 등록/수정 요청 DTO입니다.
 *
 * <p>대상 엔드포인트:</p>
 * <ul>
 *   <li>POST /api/permission</li>
 *   <li>PUT /api/permission/{permId}</li>
 * </ul>
 *
 * @param permCode 권한 코드(필수, 유니크)
 * @param permName 권한 이름(필수)
 * @param resourceType 리소스 타입(필수)
 * @param matchType 매칭 타입(선택, null이면 PREFIX 기본값 적용)
 * @param resource 리소스 문자열(필수)
 * @param httpMethod HTTP 메서드(선택, null이면 모든 메서드 허용)
 * @param description 권한 설명(선택)
 * @param isActive 활성 여부(선택, null이면 true 기본값 적용)
 * @param createdBy 생성자 식별자(선택)
 * @param updatedBy 수정자 식별자(선택)
 */
public record PermissionUpsertRequest(

        @NotBlank(message = "permCode는 필수입니다.")
        String permCode,

        @NotBlank(message = "permName은 필수입니다.")
        String permName,

        @NotNull(message = "resourceType은 필수입니다.")
        TcUiPermissionResourceType resourceType,

        TcUiPermissionMatchType matchType,

        @NotBlank(message = "resource는 필수입니다.")
        String resource,

        String httpMethod,

        String description,

        Boolean isActive,

        String createdBy,

        String updatedBy
) {
}

