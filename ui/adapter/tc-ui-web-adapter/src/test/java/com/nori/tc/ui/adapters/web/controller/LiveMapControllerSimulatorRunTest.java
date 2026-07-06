package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.dto.request.LiveMapSimulatorRunCreateRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.simulator.LiveMapSimulatorRunFacade;
import com.nori.tc.ui.adapters.web.simulator.SimulatorRunResponse;
import com.nori.tc.ui.core.service.LiveMapQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AC-2 [R-1 Run 경로] routing — 모달 시작이 simulator orchestrator Run 경로(facade)로 간다(always-on, M7 cutover).
 * plain JUnit + Mockito(ui-backend 컨벤션).
 */
@ExtendWith(MockitoExtension.class)
class LiveMapControllerSimulatorRunTest {

    @Mock
    private LiveMapQueryService liveMapQueryService;

    @Mock
    private LiveMapSimulatorRunFacade simulatorRunFacade;

    @Test
    void startSimulatorRunRoutesToOrchestrator() {
        when(simulatorRunFacade.startRun("E2E", "normal-flow", List.of("DIFF_001")))
                .thenReturn(new SimulatorRunResponse("run-1", "ALL", "RUNNING", List.of(), "2026-07-06T00:00:00Z", "normal-flow"));
        final LiveMapController controller = new LiveMapController(liveMapQueryService, simulatorRunFacade);

        final ResponseEntity<ApiResponse<SimulatorRunResponse>> response = controller.startSimulatorRun(
                new LiveMapSimulatorRunCreateRequest("E2E", "normal-flow", List.of("DIFF_001")));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("run-1", response.getBody().data().runId(), "orchestrator runId 반환");
        verify(simulatorRunFacade).startRun("E2E", "normal-flow", List.of("DIFF_001"));
    }

    @Test
    void listSimulatorRunsReturnsApiResponseListPreservingCreatedAtAndScenarioId() {
        // AC-B2: 목록이 ApiResponse<List<>> 로 감싸지고, 각 원소가 orchestrator 응답의 createdAt/scenarioId 를 보존.
        when(simulatorRunFacade.listRuns()).thenReturn(List.of(
                new SimulatorRunResponse("run-1", "ALL", "TERMINAL", List.of(), "2026-07-06T01:00:00Z", "normal-flow"),
                new SimulatorRunResponse("run-2", "EQUIPMENT_ONLY", "RUNNING", List.of(), "2026-07-06T00:00:00Z", "modbus-tcp-normal-flow")));
        final LiveMapController controller = new LiveMapController(liveMapQueryService, simulatorRunFacade);

        final ResponseEntity<ApiResponse<List<SimulatorRunResponse>>> response = controller.listSimulatorRuns();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        final List<SimulatorRunResponse> runs = response.getBody().data();
        assertEquals(2, runs.size(), "목록 2건");
        assertEquals("2026-07-06T01:00:00Z", runs.get(0).createdAt(), "createdAt 보존");
        assertEquals("normal-flow", runs.get(0).scenarioId(), "scenarioId 보존");
        verify(simulatorRunFacade).listRuns();
    }
}
