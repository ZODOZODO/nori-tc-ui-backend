package com.nori.tc.ui.adapters.web.simulator;

import com.nori.tc.ui.adapters.web.config.UiDualRequestProperties;
import com.nori.tc.ui.adapters.web.config.UiSimulatorProperties;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.service.DemoRunConnectionGate;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R-3/R-4 wire — 실사용자가 ui-front 모달에서 타는 Run 경로(LiveMapController → 본 facade → orchestrator)가
 * orchestrator createRun 전에 {@link DemoRunConnectionGate} 로 ACTIVE 대상 연결을 보장하고, stop 시 해제하는지 검증.
 *
 * <p>mock 의의: gate 호출이 누락되거나(잘못된 통합 지점) createRun 뒤로 밀리면 FAIL 한다 — ENDED 인 ACTIVE 설비
 * (PLC_MBT_001 등)가 연결되지 않는 실제 결함을 재현/방지한다(무력 mock 아님).
 */
@ExtendWith(MockitoExtension.class)
class LiveMapSimulatorRunFacadeTest {

    @Mock
    private SimulatorOrchestratorClient client;

    @Mock
    private EqpManagementService eqpManagementService;

    @Mock
    private DemoRunConnectionGate connectionGate;

    private LiveMapSimulatorRunFacade facade;

    @BeforeEach
    void setUp() {
        facade = new LiveMapSimulatorRunFacade(
                new UiSimulatorProperties(),
                client,
                new SimulatorModeMapper(),
                new SimulatorTargetAssembler(eqpManagementService),
                connectionGate,
                new UiDualRequestProperties());
    }

    @Test
    void allModeCreatesRunThenConnectsThenActivatesUpstreamInOrder() {
        when(eqpManagementService.getManageDetail("PLC_MBT_001"))
                .thenReturn(Optional.of(snapshot("PLC_MBT_001", "1")));
        final SimulatorRunResponse expected = runResponse("RUN-1");
        when(client.createRun(any())).thenReturn(expected);

        final SimulatorRunResponse actual = facade.startRun("E2E", "default", List.of("PLC_MBT_001"));

        assertSame(expected, actual);

        // ALL 연결-후-MES 배리어 순서: createRun(설비 bind, MES 보류) → ACTIVE 연결 → PASSIVE 연결 확인 → activateUpstream(MES).
        final var ordered = inOrder(client, connectionGate);
        final ArgumentCaptor<SimulatorCreateRunRequest> captor = ArgumentCaptor.forClass(SimulatorCreateRunRequest.class);
        ordered.verify(client).createRun(captor.capture());
        ordered.verify(connectionGate).ensureActiveTargetsConnected(eq(List.of("PLC_MBT_001")), anyLong());
        ordered.verify(connectionGate).awaitPassiveTargetsConnected(eq(List.of("PLC_MBT_001")), anyLong());
        ordered.verify(client).activateUpstream("RUN-1");
        assertEquals("ALL", captor.getValue().mode());
        assertEquals("default", captor.getValue().scenarioId());
        assertEquals("PLC_MBT_001", captor.getValue().targets().getFirst().eqpId());
    }

    @Test
    void startRunRolledBackWhenConnectionGateFails() {
        when(eqpManagementService.getManageDetail("PLC_MBT_001"))
                .thenReturn(Optional.of(snapshot("PLC_MBT_001", "1")));
        when(client.createRun(any())).thenReturn(runResponse("RUN-1"));
        when(connectionGate.ensureActiveTargetsConnected(eq(List.of("PLC_MBT_001")), anyLong()))
                .thenThrow(new UiConflictException("ACTIVE 대상 연결 실패"));

        assertThrows(UiConflictException.class,
                () -> facade.startRun("E2E", "default", List.of("PLC_MBT_001")));

        // 연결 실패 → MES 활성화 안 함 + run 중단·해제로 보상(조용한 부분 시작 금지).
        verify(client, never()).activateUpstream(anyString());
        verify(client).stopRun("RUN-1");
        verify(connectionGate).releaseActiveTargets(eq(List.of("PLC_MBT_001")), anyLong());
    }

    @Test
    void equipmentOnlyConnectsActiveButDoesNotActivateUpstream() {
        when(eqpManagementService.getManageDetail("PLC_MBT_001"))
                .thenReturn(Optional.of(snapshot("PLC_MBT_001", "1")));
        when(client.createRun(any())).thenReturn(runResponse("RUN-2"));

        facade.startRun("SOCKET_ONLY", "default", List.of("PLC_MBT_001"));

        final ArgumentCaptor<SimulatorCreateRunRequest> captor = ArgumentCaptor.forClass(SimulatorCreateRunRequest.class);
        verify(client).createRun(captor.capture());
        assertEquals("EQUIPMENT_ONLY", captor.getValue().mode());
        verify(connectionGate).ensureActiveTargetsConnected(eq(List.of("PLC_MBT_001")), anyLong());
        // EQUIPMENT_ONLY: MES 없음 → activateUpstream / awaitPassive 호출 안 함(설비 시나리오는 handshake 시 시작·loop).
        verify(client, never()).activateUpstream(anyString());
        verify(connectionGate, never()).awaitPassiveTargetsConnected(any(), anyLong());
    }

    @Test
    void upstreamOnlyIssuesBestEffortStartAndDoesNotActivateUpstream() {
        when(eqpManagementService.getManageDetail("PLC_MBT_001"))
                .thenReturn(Optional.of(snapshot("PLC_MBT_001", "1")));
        when(client.createRun(any())).thenReturn(runResponse("RUN-3"));

        facade.startRun("MES_ONLY", "default", List.of("PLC_MBT_001"));

        final ArgumentCaptor<SimulatorCreateRunRequest> captor = ArgumentCaptor.forClass(SimulatorCreateRunRequest.class);
        verify(client).createRun(captor.capture());
        assertEquals("UPSTREAM_ONLY", captor.getValue().mode());
        verify(connectionGate).startActiveTargetsBestEffort(eq(List.of("PLC_MBT_001")), anyLong());
        // UPSTREAM_ONLY: MES 는 createRun 에서 주입·loop → activateUpstream 안 함. 블로킹 연결(ensureActive)도 안 함.
        verify(client, never()).activateUpstream(anyString());
        verify(connectionGate, never()).ensureActiveTargetsConnected(any(), anyLong());
    }

    @Test
    void stopRunCapturesTargetsThenReleasesActiveConnections() {
        when(client.getRun("RUN-1")).thenReturn(new SimulatorRunResponse(
                "RUN-1", "ALL", "RUNNING",
                List.of(
                        new SimulatorRunResponse.SimulatorTargetResponse("CLN_001", "RUNNING", 0, 0, List.of()),
                        new SimulatorRunResponse.SimulatorTargetResponse("PLC_MBT_001", "RUNNING", 0, 0, List.of())
                )));
        final SimulatorRunResponse stopped = runResponse("RUN-1");
        when(client.stopRun("RUN-1")).thenReturn(stopped);

        final SimulatorRunResponse actual = facade.stopRun("RUN-1");

        assertSame(stopped, actual);

        // 순서: 대상 확보(getRun) → stopRun → ACTIVE 연결 해제(release).
        final var ordered = inOrder(client, connectionGate);
        ordered.verify(client).getRun("RUN-1");
        ordered.verify(client).stopRun("RUN-1");
        ordered.verify(connectionGate)
                .releaseActiveTargets(eq(List.of("CLN_001", "PLC_MBT_001")), anyLong());
    }

    private static SimulatorRunResponse runResponse(final String runId) {
        return new SimulatorRunResponse(runId, "ALL", "RUNNING", List.of());
    }

    private static EqpManagementSnapshot snapshot(final String eqpId, final String... portIds) {
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
}
