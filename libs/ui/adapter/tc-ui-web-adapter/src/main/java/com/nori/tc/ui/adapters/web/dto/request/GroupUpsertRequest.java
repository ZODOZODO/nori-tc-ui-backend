package com.nori.tc.ui.adapters.web.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 그룹 정보 등록/수정 요청 DTO입니다.
 *
 * <p>대상 엔드포인트:</p>
 * <ul>
 *   <li>POST /api/group</li>
 *   <li>PUT /api/group/{groupId}</li>
 * </ul>
 *
 * @param groupCode 그룹 코드(필수, 유니크)
 * @param groupName 그룹 이름(필수)
 * @param description 그룹 설명(선택)
 * @param isActive 활성 여부(선택, null이면 true 기본값 적용)
 */
public record GroupUpsertRequest(

        @NotBlank(message = "groupCode는 필수입니다.")
        String groupCode,

        @NotBlank(message = "groupName은 필수입니다.")
        String groupName,

        String description,

        Boolean isActive
) {
}

