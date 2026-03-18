package com.nori.tc.ui.adapters.web.dto.response;

/**
 * MDF(XML) 상세 응답 DTO입니다.
 *
 * @param name MDF 이름
 * @param xml XML 원문 문자열
 */
public record ModelMdfContentResponse(
        String name,
        String xml
) {
}
