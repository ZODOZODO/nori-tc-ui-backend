package com.nori.tc.ui.adapters.web.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record DemoRunCreateRequest(
        String planId,
        @Min(1) int defaultRepeatCount,
        @Valid @NotEmpty List<DemoTargetRequest> targets
) {
    public record DemoTargetRequest(
            @NotBlank String eqpId,
            @Min(1) Integer repeatCount
    ) {
    }
}
