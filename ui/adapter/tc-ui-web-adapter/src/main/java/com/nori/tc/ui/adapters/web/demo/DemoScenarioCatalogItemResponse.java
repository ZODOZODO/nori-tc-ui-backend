package com.nori.tc.ui.adapters.web.demo;

import java.util.List;

public record DemoScenarioCatalogItemResponse(
        String scenarioId,
        int version,
        String name,
        String description,
        List<String> supportedTestTypes,
        boolean enabled
) {
}
