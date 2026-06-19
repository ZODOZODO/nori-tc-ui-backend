package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.controller.support.LiveMapResponseSupport;
import com.nori.tc.ui.adapters.web.demo.DemoJobCreateResponse;
import com.nori.tc.ui.adapters.web.demo.DemoJobDetailResponse;
import com.nori.tc.ui.adapters.web.demo.DemoJobSummaryResponse;
import com.nori.tc.ui.adapters.web.demo.DemoScenarioCatalogItemResponse;
import com.nori.tc.ui.adapters.web.demo.DemoTesterClient;
import com.nori.tc.ui.adapters.web.demo.DemoTesterCreateJobsRequest;
import com.nori.tc.ui.adapters.web.dto.request.LiveMapDemoJobCreateRequest;
import com.nori.tc.ui.adapters.web.dto.request.LiveMapSimulatorRunCreateRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.LiveMapResponse;
import com.nori.tc.ui.adapters.web.simulator.LiveMapSimulatorRunFacade;
import com.nori.tc.ui.adapters.web.simulator.SimulatorRunResponse;
import com.nori.tc.ui.adapters.web.simulator.SimulatorScenarioCatalogItem;
import com.nori.tc.ui.core.service.LiveMapQueryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
    private final LiveMapSimulatorRunFacade simulatorRunFacade;

    public LiveMapController(
            final LiveMapQueryService liveMapQueryService,
            final DemoTesterClient demoTesterClient,
            final LiveMapSimulatorRunFacade simulatorRunFacade
    ) {
        this.liveMapQueryService = Objects.requireNonNull(liveMapQueryService, "liveMapQueryService is null");
        this.demoTesterClient = Objects.requireNonNull(demoTesterClient, "demoTesterClient is null");
        this.simulatorRunFacade = Objects.requireNonNull(simulatorRunFacade, "simulatorRunFacade is null");
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

    // ===== 신규 simulator orchestrator Run 경로(R-1) — demo-tester job 경로와 병행(토글). =====

    @PostMapping("/demo-runs")
    public ResponseEntity<ApiResponse<SimulatorRunResponse>> startSimulatorRun(
            @Valid @RequestBody final LiveMapSimulatorRunCreateRequest request
    ) {
        if (!simulatorRunFacade.isEnabled()) {
            return simulatorDisabled();
        }
        final SimulatorRunResponse response = simulatorRunFacade.startRun(
                request.testType(), request.scenarioId(), request.eqpIds());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/demo-runs/{runId}")
    public ResponseEntity<ApiResponse<SimulatorRunResponse>> getSimulatorRun(
            @PathVariable final String runId
    ) {
        if (!simulatorRunFacade.isEnabled()) {
            return simulatorDisabled();
        }
        return ResponseEntity.ok(ApiResponse.success(simulatorRunFacade.getRun(runId)));
    }

    @PostMapping("/demo-runs/{runId}/stop")
    public ResponseEntity<ApiResponse<SimulatorRunResponse>> stopSimulatorRun(
            @PathVariable final String runId
    ) {
        if (!simulatorRunFacade.isEnabled()) {
            return simulatorDisabled();
        }
        return ResponseEntity.ok(ApiResponse.success(simulatorRunFacade.stopRun(runId)));
    }

    @PostMapping("/demo-runs/{runId}/targets/{eqpId}/stop")
    public ResponseEntity<ApiResponse<SimulatorRunResponse>> stopSimulatorTarget(
            @PathVariable final String runId,
            @PathVariable final String eqpId
    ) {
        if (!simulatorRunFacade.isEnabled()) {
            return simulatorDisabled();
        }
        return ResponseEntity.ok(ApiResponse.success(simulatorRunFacade.stopTarget(runId, eqpId)));
    }

    @GetMapping("/demo-runs/scenarios")
    public ResponseEntity<ApiResponse<List<SimulatorScenarioCatalogItem>>> getSimulatorScenarios(
            @RequestParam(required = false) final String mode
    ) {
        if (!simulatorRunFacade.isEnabled()) {
            return simulatorDisabled();
        }
        return ResponseEntity.ok(ApiResponse.success(simulatorRunFacade.getScenarios(mode)));
    }

    /** 토글 off — 신규 orchestrator Run 경로 비활성. 503 으로 프론트가 기존 job 경로(demo-tester)로 폴백(R-8 롤백). */
    private static <T> ResponseEntity<ApiResponse<T>> simulatorDisabled() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("SIMULATOR_DISABLED", "신규 simulator Run 경로가 비활성화되어 있습니다."));
    }
}
