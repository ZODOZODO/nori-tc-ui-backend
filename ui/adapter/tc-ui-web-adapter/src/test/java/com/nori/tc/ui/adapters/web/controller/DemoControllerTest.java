package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.demo.DemoRunResponse;
import com.nori.tc.ui.adapters.web.demo.DemoTargetResponse;
import com.nori.tc.ui.adapters.web.demo.DemoTesterClient;
import com.nori.tc.ui.adapters.web.demo.DemoTesterCreateRunRequest;
import com.nori.tc.ui.adapters.web.demo.DemoTesterTargetStartRequest;
import com.nori.tc.ui.adapters.web.dto.request.DemoRunCreateRequest;
import com.nori.tc.ui.adapters.web.dto.request.DemoTargetStartRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.service.EqpManagementService;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpPortStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DemoControllerTest {

    @Mock
    private DemoTesterClient demoTesterClient;

    @Mock
    private EqpManagementService eqpManagementService;

    private DemoController demoController;

    @BeforeEach
    void setUp() {
        demoController = new DemoController(demoTesterClient, eqpManagementService);
    }

    @Test
    void createRunResolvesAvailablePortsAndDelegatesToDemoTester() {
        when(eqpManagementService.getManageDetail("EQP-01")).thenReturn(Optional.of(snapshot("EQP-01", "2", "3", "4")));
        when(demoTesterClient.createRun(org.mockito.ArgumentMatchers.any())).thenReturn(sampleRunResponse());

        ResponseEntity<ApiResponse<DemoRunResponse>> response = demoController.createRun(
                new DemoRunCreateRequest("e2e-demo", 10, List.of(new DemoRunCreateRequest.DemoTargetRequest("EQP-01", 2)))
        );

        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().success());
        assertEquals("RUN-01", response.getBody().data().runId());

        ArgumentCaptor<DemoTesterCreateRunRequest> captor = ArgumentCaptor.forClass(DemoTesterCreateRunRequest.class);
        verify(demoTesterClient).createRun(captor.capture());
        DemoTesterCreateRunRequest request = captor.getValue();
        assertEquals("e2e-demo", request.planId());
        assertEquals(10, request.defaultRepeatCount());
        assertEquals(List.of("2", "3", "4"), request.targets().getFirst().availablePortIds());
        assertEquals(2, request.targets().getFirst().repeatCount());
    }

    @Test
    void startAndStopEndpointsDelegateToDemoTester() {
        when(eqpManagementService.getManageDetail("EQP-01")).thenReturn(Optional.of(snapshot("EQP-01", "1", "2")));
        when(demoTesterClient.startTarget(eq("RUN-01"), eq("EQP-01"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(sampleRunResponse());
        when(demoTesterClient.stopTarget("RUN-01", "EQP-01")).thenReturn(sampleRunResponse());
        when(demoTesterClient.stopRun("RUN-01")).thenReturn(sampleRunResponse());

        demoController.startTarget("RUN-01", "EQP-01", new DemoTargetStartRequest(3));
        demoController.stopTarget("RUN-01", "EQP-01");
        demoController.stopRun("RUN-01");

        ArgumentCaptor<DemoTesterTargetStartRequest> captor = ArgumentCaptor.forClass(DemoTesterTargetStartRequest.class);
        verify(demoTesterClient).startTarget(eq("RUN-01"), eq("EQP-01"), captor.capture());
        assertEquals(List.of("1", "2"), captor.getValue().availablePortIds());
        assertEquals(3, captor.getValue().repeatCount());
        verify(demoTesterClient).stopTarget("RUN-01", "EQP-01");
        verify(demoTesterClient).stopRun("RUN-01");
    }

    @Test
    void getEndpointsExposePollingFriendlyFields() {
        when(demoTesterClient.getRun("RUN-01")).thenReturn(sampleRunResponse());
        when(demoTesterClient.getTargets("RUN-01")).thenReturn(sampleRunResponse().targets());

        ResponseEntity<ApiResponse<DemoRunResponse>> runResponse = demoController.getRun("RUN-01");
        ResponseEntity<ApiResponse<List<DemoTargetResponse>>> targetsResponse = demoController.getTargets("RUN-01");

        assertNotNull(runResponse.getBody());
        assertEquals("RUNNING", runResponse.getBody().data().state());
        assertEquals(1, runResponse.getBody().data().targets().getFirst().startedCount());
        assertEquals(0, runResponse.getBody().data().targets().getFirst().completedCount());
        assertEquals(Set.of("socket-1"), runResponse.getBody().data().targets().getFirst().activeSessionIds());

        assertNotNull(targetsResponse.getBody());
        assertEquals("EQP-01", targetsResponse.getBody().data().getFirst().eqpId());
        assertEquals(0, targetsResponse.getBody().data().getFirst().remainingCount());
    }

    private static EqpManagementSnapshot snapshot(String eqpId, String... portIds) {
        return new EqpManagementSnapshot(
                new UiEqp(1L, eqpId, 1L, UiProtocolType.SOCKET, "ACTIVE", false, 0, "127.0.0.1", 6101, 1L, "EDIT", null, null, true, OffsetDateTime.now(), OffsetDateTime.now(), "SYSTEM", "SYSTEM"),
                null,
                null,
                null,
                null,
                null,
                null,
                "CONNECTED",
                java.util.Arrays.stream(portIds)
                        .map(portId -> new UiEqpPortStatus(1L, 1L, portId, null, "IDLE", null, null, null, OffsetDateTime.now()))
                        .toList(),
                List.of(),
                List.of(),
                null,
                null
        );
    }

    private static DemoRunResponse sampleRunResponse() {
        return new DemoRunResponse(
                "RUN-01",
                "RUNNING",
                10,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of(new DemoTargetResponse("EQP-01", "EQP-01", List.of("1"), 1, "RUNNING", 1, 0, 0, List.of("1"), Set.of("socket-1")))
        );
    }
}
