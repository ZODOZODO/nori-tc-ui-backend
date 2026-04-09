package com.nori.tc.ui.adapters.web.demo;

import java.util.List;

public record DemoJobDetailResponse(
        DemoJobSummaryResponse job,
        List<DemoJobEventResponse> events
) {
}
