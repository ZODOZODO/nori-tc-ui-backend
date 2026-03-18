package com.nori.tc.ui.adapters.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * branch model 생성 요청 DTO입니다.
 *
 * @param suffix branch 이름에 포함할 사용자 입력 suffix
 * @param sourceModelVersionKey 복제 기준 root model_version_key (미지정 시 최신 버전 사용)
 */
public record ModelBranchCreateRequest(

        @NotBlank(message = "suffix는 필수입니다.")
        String suffix,

        @Positive(message = "sourceModelVersionKey는 1 이상이어야 합니다.")
        Long sourceModelVersionKey
) {
}
