package com.nori.tc.ui.adapters.web.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * PUT /api/model/{modelVersionKey}/details/{detailNode} 요청 본문 DTO입니다.
 *
 * @param rows 저장할 상세 row 목록
 */
public record ModelDetailSaveRequest(

        @NotNull(message = "rows는 필수입니다.")
        @Valid
        List<ModelDetailSaveRowItem> rows
) {

    /**
     * 상세 테이블 단건 row 저장 항목입니다.
     *
     * @param id 기존 row 식별자. 신규 row는 null/blank 허용
     * @param values 컬럼 순서에 맞는 셀 값 목록
     */
    public record ModelDetailSaveRowItem(
            String id,

            @NotNull(message = "values는 필수입니다.")
            List<String> values
    ) {
    }
}
