package com.nori.tc.ui.adapters.web.dto.request;

import jakarta.validation.constraints.Min;

public record DemoTargetStartRequest(
        @Min(1) int repeatCount
) {
}
