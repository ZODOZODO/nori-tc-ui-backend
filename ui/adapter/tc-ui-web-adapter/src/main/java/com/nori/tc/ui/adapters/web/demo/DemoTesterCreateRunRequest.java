package com.nori.tc.ui.adapters.web.demo;

import java.util.List;

public record DemoTesterCreateRunRequest(
        String planId,
        int defaultRepeatCount,
        List<DemoTesterTargetRequest> targets
) {
    public record DemoTesterTargetRequest(
            String eqpId,
            String eqpName,
            List<String> availablePortIds,
            Integer repeatCount
    ) {
    }
}
