package com.nori.tc.ui.adapters.web.dto.request;

import com.nori.tc.db.domain.common.model.ProtocolType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * root model 생성 요청 DTO입니다.
 *
 * @param modelName 생성할 모델 이름
 * @param commInterface 통신 인터페이스
 * @param maker 제조사
 */
public record ModelRootCreateRequest(

        @NotBlank(message = "modelName은 필수입니다.")
        @Size(max = 1000, message = "modelName은 1000자 이하여야 합니다.")
        String modelName,

        @NotNull(message = "commInterface는 필수입니다.")
        ProtocolType commInterface,

        String maker
) {
}
