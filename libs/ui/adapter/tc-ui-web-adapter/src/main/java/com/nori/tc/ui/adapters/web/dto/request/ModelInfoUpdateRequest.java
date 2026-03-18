package com.nori.tc.ui.adapters.web.dto.request;

/**
 * root model 공통 정보 수정 요청 DTO입니다.
 *
 * @param maker 변경할 제조사
 */
public record ModelInfoUpdateRequest(
        String maker
) {
}
