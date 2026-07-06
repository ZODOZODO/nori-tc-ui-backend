package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.controller.support.LiveMapResponseSupport;
import com.nori.tc.ui.adapters.web.dto.request.LiveMapSimulatorRunCreateRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.LiveMapResponse;
import com.nori.tc.ui.adapters.web.simulator.LiveMapSimulatorRunFacade;
import com.nori.tc.ui.adapters.web.simulator.SimulatorRunResponse;
import com.nori.tc.ui.adapters.web.simulator.SimulatorScenarioCatalogItem;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.service.LiveMapQueryService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * live-map 조회 REST API 컨트롤러입니다.
 */
@RestController
@RequestMapping("/api/live-map")
public class LiveMapController {

    private final LiveMapQueryService liveMapQueryService;
    private final LiveMapSimulatorRunFacade simulatorRunFacade;

    public LiveMapController(
            final LiveMapQueryService liveMapQueryService,
            final LiveMapSimulatorRunFacade simulatorRunFacade
    ) {
        this.liveMapQueryService = Objects.requireNonNull(liveMapQueryService, "liveMapQueryService is null");
        this.simulatorRunFacade = Objects.requireNonNull(simulatorRunFacade, "simulatorRunFacade is null");
    }

    @GetMapping
    public ResponseEntity<ApiResponse<LiveMapResponse>> getLiveMap() {
        return ResponseEntity.ok(ApiResponse.success(
                LiveMapResponseSupport.toResponse(liveMapQueryService.getEquipmentViews())
        ));
    }

    // ===== simulator orchestrator Run 경로(R-1) — always-on(M7 cutover). =====

    @PostMapping("/demo-runs")
    public ResponseEntity<ApiResponse<SimulatorRunResponse>> startSimulatorRun(
            @Valid @RequestBody final LiveMapSimulatorRunCreateRequest request
    ) {
        final SimulatorRunResponse response = simulatorRunFacade.startRun(
                request.testType(), request.scenarioId(), request.eqpIds());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * run 이력 목록(DB 출처, R4) — '시뮬레이션 진행 현황' 이 과거·실행중 run 을 최신순으로 모두 표시(재부착 포함).
     * 권한은 기존 {@code LIVE_MAP_DEMO_RUN_READ}(PREFIX /api/live-map/demo-runs, GET)가 커버(C4, 신규 매핑 0).
     */
    @GetMapping("/demo-runs")
    public ResponseEntity<ApiResponse<List<SimulatorRunResponse>>> listSimulatorRuns() {
        return ResponseEntity.ok(ApiResponse.success(simulatorRunFacade.listRuns()));
    }

    @GetMapping("/demo-runs/{runId}")
    public ResponseEntity<ApiResponse<SimulatorRunResponse>> getSimulatorRun(
            @PathVariable final String runId
    ) {
        return ResponseEntity.ok(ApiResponse.success(simulatorRunFacade.getRun(runId)));
    }

    @PostMapping("/demo-runs/{runId}/stop")
    public ResponseEntity<ApiResponse<SimulatorRunResponse>> stopSimulatorRun(
            @PathVariable final String runId
    ) {
        return ResponseEntity.ok(ApiResponse.success(simulatorRunFacade.stopRun(runId)));
    }

    @PostMapping("/demo-runs/{runId}/targets/{eqpId}/stop")
    public ResponseEntity<ApiResponse<SimulatorRunResponse>> stopSimulatorTarget(
            @PathVariable final String runId,
            @PathVariable final String eqpId
    ) {
        return ResponseEntity.ok(ApiResponse.success(simulatorRunFacade.stopTarget(runId, eqpId)));
    }

    @GetMapping("/demo-runs/scenarios")
    public ResponseEntity<ApiResponse<List<SimulatorScenarioCatalogItem>>> getSimulatorScenarios(
            @RequestParam(required = false) final String mode
    ) {
        return ResponseEntity.ok(ApiResponse.success(simulatorRunFacade.getScenarios(mode)));
    }

    /**
     * 시나리오 업로드(R-6) — 마크다운 파일(.scenario.md) multipart 수신 → UTF-8 문자열 → orchestrator 검증/적재.
     * 업로드 권한은 기존 {@code LIVE_MAP_DEMO_RUN_WRITE}(POST, PREFIX /api/live-map/demo-runs)가 커버(R-7, 신규 매핑 0).
     */
    @PostMapping(path = "/demo-runs/scenarios", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<SimulatorScenarioCatalogItem>> uploadSimulatorScenario(
            @RequestParam("file") final MultipartFile file
    ) {
        final String sourceText = new String(readBytes(file), StandardCharsets.UTF_8);
        return ResponseEntity.ok(ApiResponse.success(simulatorRunFacade.uploadScenario(sourceText)));
    }

    /**
     * 시나리오 삭제(R-6) — 신규 권한 {@code LIVE_MAP_DEMO_RUN_SCENARIO_DELETE}(DELETE, PREFIX /api/live-map/demo-runs/scenarios)
     * 가 게이팅(R-7, ADMIN 매핑). 시드 보호(409)/미존재(404)는 client 가 Ui 예외로 매핑.
     */
    @DeleteMapping("/demo-runs/scenarios/{scenarioId}")
    public ResponseEntity<ApiResponse<Void>> deleteSimulatorScenario(@PathVariable final String scenarioId) {
        simulatorRunFacade.deleteScenario(scenarioId);
        return ResponseEntity.ok(ApiResponse.<Void>success(null));
    }

    private static byte[] readBytes(final MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new UiBadRequestException("업로드 파일이 비어 있습니다.");
        }
        try {
            return file.getBytes();
        } catch (final java.io.IOException exception) {
            throw new UiBadRequestException("업로드 파일을 읽지 못했습니다: " + exception.getMessage());
        }
    }
}
