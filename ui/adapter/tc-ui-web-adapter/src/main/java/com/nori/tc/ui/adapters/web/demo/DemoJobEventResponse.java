package com.nori.tc.ui.adapters.web.demo;

import java.time.OffsetDateTime;

public record DemoJobEventResponse(
        int eventSeq,
        String eventType,
        String eventSource,
        String stageCode,
        String message,
        OffsetDateTime occurredAt
) {
}
