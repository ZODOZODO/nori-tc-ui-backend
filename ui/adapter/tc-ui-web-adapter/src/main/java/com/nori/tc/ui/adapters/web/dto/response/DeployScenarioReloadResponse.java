package com.nori.tc.ui.adapters.web.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * scenario reload 응답입니다.
 */
public record DeployScenarioReloadResponse(
        String status,
        int catalogVersionBefore,
        int catalogVersionAfter,
        int loadedScenarioCount,
        OffsetDateTime reloadedAt,
        List<String> validationErrors
) {
}
