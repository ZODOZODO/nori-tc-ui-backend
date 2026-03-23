package com.nori.tc.ui.adapters.web.demo;

import java.util.List;
import java.util.Set;

public record DemoTargetResponse(
        String eqpId,
        String eqpName,
        List<String> availablePortIds,
        int repeatCount,
        String state,
        int startedCount,
        int completedCount,
        int remainingCount,
        List<String> plannedPortOrder,
        Set<String> activeSessionIds
) {
}
