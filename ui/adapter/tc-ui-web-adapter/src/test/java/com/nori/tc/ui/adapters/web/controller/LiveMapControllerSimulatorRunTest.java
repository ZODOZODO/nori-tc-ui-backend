package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.demo.DemoTesterClient;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AC-2 [R-1 Run 경로] routing — 모달 시작이 신규 orchestrator Run 경로(facade)로 가고 demo-tester job 경로
 * (DemoTesterClient)는 미호출. 토글 off 면 503(프론트 job 폴백). plain JUnit + Mockito(ui-backend 컨벤션).
 */
@ExtendWith(MockitoExtension.class)
class LiveMapControllerSimulatorRunTest {

    @Mock
    private LiveMapQueryService liveMapQueryService;

    @Mock
    private DemoTesterClient demoTesterClient;

    @Mock
    private LiveMapSimulatorRunFacade simulatorRunFacade;

    @Test
    void startSimulatorRunRoutesToOrchestratorAndNotDemoTesterJobPath() {
        when(simulatorRunFacade.isEnabled()).thenReturn(true);
        when(simulatorRunFacade.startRun("E2E", "normal-flow", List.of("DIFF_001")))
                .thenReturn(new SimulatorRunResponse("run-1", "ALL", "RUNNING", List.of()));
        final LiveMapController controller = new LiveMapController(liveMapQueryService, demoTesterClient, simulatorRunFacade);

        final ResponseEntity<ApiResponse<SimulatorRunResponse>> response = controller.startSimulatorRun(
                new LiveMapSimulatorRunCreateRequest("E2E", "normal-flow", List.of("DIFF_001")));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("run-1", response.getBody().data().runId(), "orchestrator runId 반환");
        verify(simulatorRunFacade).startRun("E2E", "normal-flow", List.of("DIFF_001"));
        verifyNoInteractions(demoTesterClient); // demo-tester job 경로 미호출(전환 확인).
    }

    @Test
    void disabledToggleReturns503AndSkipsOrchestrator() {
        when(simulatorRunFacade.isEnabled()).thenReturn(false);
        final LiveMapController controller = new LiveMapController(liveMapQueryService, demoTesterClient, simulatorRunFacade);

        final ResponseEntity<ApiResponse<SimulatorRunResponse>> response = controller.startSimulatorRun(
                new LiveMapSimulatorRunCreateRequest("E2E", "normal-flow", List.of("DIFF_001")));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode(), "토글 off → 503(job 폴백)");
        verify(simulatorRunFacade, never()).startRun(any(), any(), any());
        verifyNoInteractions(demoTesterClient);
    }
}
