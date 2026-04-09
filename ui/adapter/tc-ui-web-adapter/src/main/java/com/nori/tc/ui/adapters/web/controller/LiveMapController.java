package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.controller.support.LiveMapResponseSupport;
import com.nori.tc.ui.adapters.web.demo.DemoJobCreateResponse;
import com.nori.tc.ui.adapters.web.demo.DemoJobDetailResponse;
import com.nori.tc.ui.adapters.web.demo.DemoJobSummaryResponse;
import com.nori.tc.ui.adapters.web.demo.DemoScenarioCatalogItemResponse;
import com.nori.tc.ui.adapters.web.demo.DemoTesterClient;
import com.nori.tc.ui.adapters.web.demo.DemoTesterCreateJobsRequest;
import com.nori.tc.ui.adapters.web.dto.request.LiveMapDemoJobCreateRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.LiveMapResponse;
import com.nori.tc.ui.core.service.LiveMapQueryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * live-map 조회 REST API 컨트롤러입니다.
 */
@RestController
@RequestMapping("/api/live-map")
public class LiveMapController {

    private final LiveMapQueryService liveMapQueryService;
    private final DemoTesterClient demoTesterClient;

    public LiveMapController(
            final LiveMapQueryService liveMapQueryService,
            final DemoTesterClient demoTesterClient
    ) {
        this.liveMapQueryService = Objects.requireNonNull(liveMapQueryService, "liveMapQueryService is null");
        this.demoTesterClient = Objects.requireNonNull(demoTesterClient, "demoTesterClient is null");
    }

    @GetMapping
    public ResponseEntity<ApiResponse<LiveMapResponse>> getLiveMap() {
        return ResponseEntity.ok(ApiResponse.success(
                LiveMapResponseSupport.toResponse(liveMapQueryService.getEquipmentViews())
        ));
    }

    @GetMapping("/demo-scenarios")
    public ResponseEntity<ApiResponse<List<DemoScenarioCatalogItemResponse>>> getDemoScenarios(
            @RequestParam(required = false) final String testType
    ) {
        return ResponseEntity.ok(ApiResponse.success(demoTesterClient.getScenarios(testType)));
    }

    @PostMapping("/demo-jobs")
    public ResponseEntity<ApiResponse<DemoJobCreateResponse>> createDemoJobs(
            @Valid @RequestBody final LiveMapDemoJobCreateRequest request
    ) {
        final DemoJobCreateResponse response = demoTesterClient.createJobs(new DemoTesterCreateJobsRequest(
                request.testType(),
                request.scenarioId(),
                request.scenarioVersion(),
                request.eqpIds(),
                request.requestedBy()
        ));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/demo-jobs")
    public ResponseEntity<ApiResponse<List<DemoJobSummaryResponse>>> getDemoJobs(
            @RequestParam(required = false) final String states,
            @RequestParam(defaultValue = "100") final Integer limit
    ) {
        return ResponseEntity.ok(ApiResponse.success(demoTesterClient.getJobs(states, limit)));
    }

    @GetMapping("/demo-jobs/{jobId}")
    public ResponseEntity<ApiResponse<DemoJobDetailResponse>> getDemoJob(
            @PathVariable final String jobId
    ) {
        return ResponseEntity.ok(ApiResponse.success(demoTesterClient.getJob(jobId)));
    }

    @PostMapping("/demo-jobs/{jobId}/cancel")
    public ResponseEntity<ApiResponse<DemoJobDetailResponse>> cancelDemoJob(
            @PathVariable final String jobId
    ) {
        return ResponseEntity.ok(ApiResponse.success(demoTesterClient.cancelJob(jobId)));
    }
}
