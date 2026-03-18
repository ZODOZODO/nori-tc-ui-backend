package com.nori.tc.ui.adapters.web.dto.response;

import java.util.List;

/**
 * Model 상세 노드의 공통 테이블 row 응답 DTO입니다.
 *
 * @param id row 식별자. 저장 시 기존 row 매핑에 사용합니다.
 * @param values 컬럼 순서에 맞는 원본 셀 값 목록
 * @param previewValues 테이블에 축약 표시할 preview 셀 값 목록
 */
public record ModelDetailRowResponse(
        String id,
        List<String> values,
        List<String> previewValues
) {

    /**
     * preview를 별도로 주지 않으면 원본 값을 그대로 표시값으로 사용합니다.
     *
     * @param id row 식별자
     * @param values 원본 셀 값 목록
     */
    public ModelDetailRowResponse(final String id, final List<String> values) {
        this(id, values, null);
    }

    /**
     * preview를 별도로 주지 않으면 원본 값을 그대로 표시값으로 사용합니다.
     *
     * @param values 원본 셀 값 목록
     */
    public ModelDetailRowResponse(final List<String> values) {
        this(null, values, null);
    }

    /**
     * null-safe 불변 목록으로 정규화합니다.
     */
    public ModelDetailRowResponse {
        id = id == null ? "" : id;
        values = List.copyOf(values == null ? List.of() : values);
        previewValues = List.copyOf(previewValues == null || previewValues.isEmpty() ? values : previewValues);
    }
}
