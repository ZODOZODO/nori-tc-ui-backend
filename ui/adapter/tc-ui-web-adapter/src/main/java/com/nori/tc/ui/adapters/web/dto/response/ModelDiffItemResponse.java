package com.nori.tc.ui.adapters.web.dto.response;

import java.util.List;

/**
 * model parent commit diff의 단일 항목 응답 DTO입니다.
 *
 * @param identity 항목 식별자
 * @param branchValues branch 최신 값
 * @param parentValues parent 최신 값
 */
public record ModelDiffItemResponse(
        String identity,
        List<String> branchValues,
        List<String> parentValues
) {
}
