package com.nori.tc.ui.adapters.web.demo;

import java.util.List;

public record DemoTesterTargetStartRequest(
        String eqpName,
        List<String> availablePortIds,
        int repeatCount
) {
}
