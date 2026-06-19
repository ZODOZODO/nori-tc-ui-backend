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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    private DemoRunConnectionRegistry runRegistry;
    private LiveMapSimulatorRunFacade facade;

    @BeforeEach
    void setUp() {
        runRegistry = new DemoRunConnectionRegistry();
        facade = new LiveMapSimulatorRunFacade(
                new UiSimulatorProperties(),
                client,
                new SimulatorModeMapper(),
                new SimulatorTargetAssembler(eqpManagementService),
                connectionGate,
                new UiDualRequestProperties(),
                runRegistry);
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

        // ensureActive throw → track 미실행 → rollback claim empty → release 미호출(이중/거짓 END 0).
        // run 중단은 그대로 수행, MES 활성화는 안 함(조용한 부분 시작 금지).
        verify(client, never()).activateUpstream(anyString());
        verify(client).stopRun("RUN-1");
        verify(connectionGate, never()).releaseActiveTargets(any(), anyLong());
    }

    @Test
    void startRunTracksCapturedSetForAll() {
        // 사전 STARTED(DIFF_PRE)와 이 run 이 연결할 PLC_MBT_001 을 함께 넘긴다 — gate 는 newly-started 만 반환.
        when(eqpManagementService.getManageDetail("DIFF_PRE"))
                .thenReturn(Optional.of(snapshot("DIFF_PRE", "1")));
        when(eqpManagementService.getManageDetail("PLC_MBT_001"))
                .thenReturn(Optional.of(snapshot("PLC_MBT_001", "1")));
        when(client.createRun(any())).thenReturn(runResponse("RUN-1"));
        when(connectionGate.ensureActiveTargetsConnected(eq(List.of("DIFF_PRE", "PLC_MBT_001")), anyLong()))
                .thenReturn(Set.of("PLC_MBT_001"));

        facade.startRun("E2E", "default", List.of("DIFF_PRE", "PLC_MBT_001"));

        // AC1: 레지스트리 엔트리 = ensureActive 반환 집합. AC5: 사전 STARTED(DIFF_PRE)는 captured-set 미포함.
        final Optional<Set<String>> captured = runRegistry.claim("RUN-1");
        assertTrue(captured.isPresent());
        assertEquals(Set.of("PLC_MBT_001"), captured.get());
        assertFalse(captured.get().contains("DIFF_PRE"), "사전 STARTED 설비는 원복 대상이 아니다");
    }

    @Test
    void stopRunReleasesCapturedSetAndUntracks() {
        when(eqpManagementService.getManageDetail("DIFF_PRE"))
                .thenReturn(Optional.of(snapshot("DIFF_PRE", "1")));
        when(eqpManagementService.getManageDetail("PLC_MBT_001"))
                .thenReturn(Optional.of(snapshot("PLC_MBT_001", "1")));
        when(client.createRun(any())).thenReturn(runResponse("RUN-1"));
        when(connectionGate.ensureActiveTargetsConnected(eq(List.of("DIFF_PRE", "PLC_MBT_001")), anyLong()))
                .thenReturn(Set.of("PLC_MBT_001"));
        when(client.stopRun("RUN-1")).thenReturn(runResponse("RUN-1"));

        facade.startRun("E2E", "default", List.of("DIFF_PRE", "PLC_MBT_001"));
        facade.stopRun("RUN-1");

        // AC6/AC5: 추적된 run 의 stop 은 captured-set 으로만 release(전체 target 아님, getRun 미호출).
        verify(connectionGate).releaseActiveTargets(eq(List.of("PLC_MBT_001")), anyLong());
        verify(client, never()).getRun("RUN-1");

        // AC6: stop 이 추적을 소진했으므로 이후 reaper tick 은 같은 run 을 또 END 하지 않는다(이중 END 0).
        final DemoRunConnectionReaper reaper = new DemoRunConnectionReaper(
                client, connectionGate, runRegistry, new UiDualRequestProperties());
        reaper.reapTerminatedRuns();
        verify(connectionGate, times(1)).releaseActiveTargets(any(), anyLong());
    }

    @Test
    void rollbackReleasesCapturedSetWhenPassiveFails() {
        when(eqpManagementService.getManageDetail("PLC_MBT_001"))
                .thenReturn(Optional.of(snapshot("PLC_MBT_001", "1")));
        when(client.createRun(any())).thenReturn(runResponse("RUN-1"));
        when(connectionGate.ensureActiveTargetsConnected(eq(List.of("PLC_MBT_001")), anyLong()))
                .thenReturn(Set.of("PLC_MBT_001"));
        // ALL: ensureActive 성공(captured 기록) 후 awaitPassive 가 실패하는 경로.
        doThrow(new UiConflictException("PASSIVE 연결 실패"))
                .when(connectionGate).awaitPassiveTargetsConnected(eq(List.of("PLC_MBT_001")), anyLong());

        assertThrows(UiConflictException.class,
                () -> facade.startRun("E2E", "default", List.of("PLC_MBT_001")));

        // AC7: rollback 이 captured-set 으로 정확히 1회 release + run 중단. 추적은 소진(이중 END 0).
        verify(connectionGate).releaseActiveTargets(eq(List.of("PLC_MBT_001")), anyLong());
        verify(client).stopRun("RUN-1");
        assertFalse(runRegistry.claim("RUN-1").isPresent());
    }

    @Test
    void upstreamOnlyDoesNotTrack() {
        when(eqpManagementService.getManageDetail("PLC_MBT_001"))
                .thenReturn(Optional.of(snapshot("PLC_MBT_001", "1")));
        when(client.createRun(any())).thenReturn(runResponse("RUN-3"));

        facade.startRun("MES_ONLY", "default", List.of("PLC_MBT_001"));

        // AC11: UPSTREAM_ONLY 는 best-effort void 라 captured-set 이 없어 추적하지 않는다.
        assertFalse(runRegistry.claim("RUN-3").isPresent());
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
                        new SimulatorRunResponse.SimulatorTargetResponse("CLN_001", "RUNNING", "SOCKET", 0, 0, List.of()),
                        new SimulatorRunResponse.SimulatorTargetResponse("PLC_MBT_001", "RUNNING", "PLC", 0, 0, List.of())
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

    @Test
    void stopTargetReleasesConnectionOnlyForRunConnectedEquipmentNotPreConnected() {
        // DIFF_PRE = run 시작 전 이미 연결(captured-set 미포함), PLC_MBT_001 = 이 run 이 EQP_START 로 연결(captured 포함).
        when(eqpManagementService.getManageDetail("DIFF_PRE"))
                .thenReturn(Optional.of(snapshot("DIFF_PRE", "1")));
        when(eqpManagementService.getManageDetail("PLC_MBT_001"))
                .thenReturn(Optional.of(snapshot("PLC_MBT_001", "1")));
        when(client.createRun(any())).thenReturn(runResponse("RUN-1"));
        when(connectionGate.ensureActiveTargetsConnected(eq(List.of("DIFF_PRE", "PLC_MBT_001")), anyLong()))
                .thenReturn(Set.of("PLC_MBT_001"));
        when(client.stopTarget(eq("RUN-1"), anyString())).thenReturn(runResponse("RUN-1"));

        facade.startRun("E2E", "default", List.of("DIFF_PRE", "PLC_MBT_001"));

        // 이 run 이 연결한 PLC_MBT_001 종료 → 연결 해제(EQP_END).
        facade.stopTarget("RUN-1", "PLC_MBT_001");
        verify(client).stopTarget("RUN-1", "PLC_MBT_001");
        verify(connectionGate).releaseActiveTargets(eq(List.of("PLC_MBT_001")), anyLong());

        // 사전 연결돼 있던 DIFF_PRE 종료 → captured-set 에 없어 연결 유지(EQP_END 0).
        facade.stopTarget("RUN-1", "DIFF_PRE");
        verify(client).stopTarget("RUN-1", "DIFF_PRE");
        verify(connectionGate, never()).releaseActiveTargets(eq(List.of("DIFF_PRE")), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void stopTargetThatFinishesRunReleasesRemainingCapturedSetAndUntracks() {
        // 두 설비 모두 이 run 이 연결(captured={CLN_001, PLC_MBT_001}). PLC 종료가 run 을 CANCELED 로 종료시키는 경우,
        // reaper 가 CANCELED 를 건너뛰므로 facade 가 남은 captured 전체를 END 하고 추적을 소진해야 한다(누수 0).
        when(eqpManagementService.getManageDetail("CLN_001"))
                .thenReturn(Optional.of(snapshot("CLN_001", "1")));
        when(eqpManagementService.getManageDetail("PLC_MBT_001"))
                .thenReturn(Optional.of(snapshot("PLC_MBT_001", "1")));
        when(client.createRun(any())).thenReturn(runResponse("RUN-1"));
        when(connectionGate.ensureActiveTargetsConnected(eq(List.of("CLN_001", "PLC_MBT_001")), anyLong()))
                .thenReturn(Set.of("CLN_001", "PLC_MBT_001"));
        when(client.stopTarget("RUN-1", "PLC_MBT_001"))
                .thenReturn(new SimulatorRunResponse("RUN-1", "ALL", "CANCELED", List.of()));

        facade.startRun("E2E", "default", List.of("CLN_001", "PLC_MBT_001"));
        facade.stopTarget("RUN-1", "PLC_MBT_001");

        final ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(connectionGate).releaseActiveTargets(captor.capture(), anyLong());
        assertEquals(Set.of("CLN_001", "PLC_MBT_001"), Set.copyOf(captor.getValue()),
                "run 종료 시 남은 captured 전체 END");
        assertFalse(runRegistry.claim("RUN-1").isPresent(), "추적 소진(reaper 이중 END/누수 0)");
    }

    @Test
    void stopTargetForUntrackedRunDoesNotReleaseConnection() {
        // UPSTREAM_ONLY 는 best-effort start 라 captured-set 미추적 → per-target stop 은 연결을 끊지 않는다(실설비 대상).
        when(eqpManagementService.getManageDetail("PLC_MBT_001"))
                .thenReturn(Optional.of(snapshot("PLC_MBT_001", "1")));
        when(client.createRun(any())).thenReturn(runResponse("RUN-3"));
        when(client.stopTarget(eq("RUN-3"), anyString())).thenReturn(runResponse("RUN-3"));

        facade.startRun("MES_ONLY", "default", List.of("PLC_MBT_001"));
        facade.stopTarget("RUN-3", "PLC_MBT_001");

        verify(client).stopTarget("RUN-3", "PLC_MBT_001");
        verify(connectionGate, never()).releaseActiveTargets(any(), anyLong());
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
