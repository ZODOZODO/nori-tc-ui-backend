package com.nori.tc.ui.adapters.web.demo;

public record DemoJobRejectedEquipmentResponse(
        String eqpId,
        String reasonCode,
        String reasonMessage,
        String activeJobId
) {
}
