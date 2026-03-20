package com.nori.tc.ui.adapters.web.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * scenario validate 응답입니다.
 */
public record DeployScenarioValidationResponse(
        String status,
        OffsetDateTime validatedAt,
        int loadedScenarioCount,
        List<String> validationErrors
) {
}
