package com.nori.tc.ui.adapters.web.demo;

import java.util.List;

public record DemoTesterCreateJobsRequest(
        String testType,
        String scenarioId,
        int scenarioVersion,
        List<String> eqpIds,
        String requestedBy
) {
}
