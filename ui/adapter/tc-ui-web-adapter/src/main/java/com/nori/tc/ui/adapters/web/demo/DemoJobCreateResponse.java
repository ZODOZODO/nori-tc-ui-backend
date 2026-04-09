package com.nori.tc.ui.adapters.web.demo;

import java.util.List;

public record DemoJobCreateResponse(
        List<DemoJobSummaryResponse> acceptedJobs,
        List<DemoJobRejectedEquipmentResponse> rejectedEquipments
) {
}
