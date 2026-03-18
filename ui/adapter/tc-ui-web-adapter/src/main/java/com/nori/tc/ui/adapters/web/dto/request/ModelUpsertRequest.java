package com.nori.tc.ui.adapters.web.dto.request;

import com.nori.tc.db.domain.common.model.ModelStatus;
import com.nori.tc.db.domain.common.model.ProtocolType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 모델 정보 등록/수정 요청 DTO입니다.
 *
 * <p>대상 엔드포인트:</p>
 * <ul>
 *   <li>POST /api/model</li>
 *   <li>PUT /api/model/{modelVersionKey}</li>
 * </ul>
 *
 * @param modelName 모델 이름(필수)
 * @param modelVersion 모델 버전(필수)
 * @param commInterface 통신 인터페이스 타입(필수)
 * @param status 모델 상태(필수)
 * @param description 모델 버전 설명(선택)
 * @param maker 제조사 정보(선택)
 * @param createdBy 생성자 식별자(선택)
 * @param updatedBy 수정자 식별자(선택)
 */
public record ModelUpsertRequest(

        @NotBlank(message = "modelName은 필수입니다.")
        @Size(max = 1000, message = "modelName은 1000자 이하여야 합니다.")
        String modelName,

        @NotBlank(message = "modelVersion은 필수입니다.")
        @Size(max = 100, message = "modelVersion은 100자 이하여야 합니다.")
        String modelVersion,

        @NotNull(message = "commInterface는 필수입니다.")
        ProtocolType commInterface,

        @NotNull(message = "status는 필수입니다.")
        ModelStatus status,

        String description,

        String maker,

        String createdBy,

        String updatedBy
) {
}
