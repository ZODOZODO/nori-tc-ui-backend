package com.nori.tc.ui.adapters.web.demo;

import java.time.OffsetDateTime;
import java.util.List;

public record DemoRunResponse(
        String runId,
        String state,
        int defaultRepeatCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<DemoTargetResponse> targets
) {
}
