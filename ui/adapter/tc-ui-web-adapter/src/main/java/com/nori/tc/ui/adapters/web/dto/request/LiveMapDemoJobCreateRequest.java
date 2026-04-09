package com.nori.tc.ui.adapters.web.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record LiveMapDemoJobCreateRequest(
        @NotBlank(message = "testType은 필수입니다.")
        String testType,
        @NotBlank(message = "scenarioId는 필수입니다.")
        String scenarioId,
        @Min(value = 1, message = "scenarioVersion은 1 이상이어야 합니다.")
        int scenarioVersion,
        @NotEmpty(message = "eqpIds는 1개 이상이어야 합니다.")
        List<@NotBlank(message = "eqpId는 비어 있을 수 없습니다.") String> eqpIds,
        String requestedBy
) {
}
