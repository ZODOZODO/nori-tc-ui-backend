package com.nori.tc.ui.adapters.web.dto.response;

/**
 * 설비 파라미터 단건 응답 DTO입니다.
 *
 * @param paramName 파라미터 이름
 * @param paramValue 파라미터 값 (null 허용)
 * @param description 파라미터 설명 (null 허용)
 * @param createdBy 파라미터를 생성/체크아웃한 사용자 ID
 */
public record EqpParamResponse(
        String paramName,
        String paramValue,
        String description,
        String createdBy
) {
}
