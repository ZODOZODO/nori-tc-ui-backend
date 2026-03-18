package com.nori.tc.ui.adapters.web.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * PUT /api/eqp/{eqpId}/edit-params 요청 본문 DTO입니다.
 *
 * @param params 수정할 파라미터 목록 (필수)
 */
public record EqpParamSaveRequest(

        @NotNull(message = "params는 필수입니다.")
        @Valid
        List<EqpParamSaveItem> params
) {

    /**
     * 파라미터 단건 수정 항목입니다.
     *
     * @param paramName 파라미터 이름 (필수)
     * @param paramValue 새 파라미터 값 (null 허용)
     * @param description 새 파라미터 설명 (null 허용)
     */
    public record EqpParamSaveItem(

            @NotBlank(message = "paramName은 필수입니다.")
            String paramName,

            String paramValue,

            String description
    ) {
    }
}
