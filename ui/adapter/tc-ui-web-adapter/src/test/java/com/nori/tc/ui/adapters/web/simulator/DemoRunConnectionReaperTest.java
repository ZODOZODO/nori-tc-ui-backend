package com.nori.tc.ui.adapters.web.simulator;

import com.nori.tc.ui.adapters.web.config.UiDualRequestProperties;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.service.DemoRunConnectionGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DemoRunConnectionReaper} 의 종료 판정/오류 분기/이중 END 방지를 검증한다.
 *
 * <p>mock 의의: predicate 가 CANCELED 를 잘못 포함하거나(이중 END race), captured-set 이 아닌 전체 target 으로
 * END 하거나, 일시 오류(5xx/IO)에 거짓 원복하면 FAIL 하도록 — 각 분기를 실 결함 시나리오로 재현한다(무력 mock 아님).
 */
class DemoRunConnectionReaperTest {

    private final SimulatorOrchestratorClient client = mock(SimulatorOrchestratorClient.class);
    private final DemoRunConnectionGate connectionGate = mock(DemoRunConnectionGate.class);
    private final DemoRunConnectionRegistry registry = new DemoRunConnectionRegistry();
    private final DemoRunConnectionReaper reaper = new DemoRunConnectionReaper(
            client, connectionGate, registry, new UiDualRequestProperties());

    @Test
    @DisplayName("AC2: TERMINAL 이면 captured-set 으로 releaseActiveTargets 를 정확히 1회 호출한다")
    void reaperReleasesCapturedSetOnTerminal() {
        registry.track("RUN-1", Set.of("eqpA"));
        when(client.getRun("RUN-1")).thenReturn(run("RUN-1", "TERMINAL"));

        reaper.reapTerminatedRuns();

        final ArgumentCaptor<List<String>> captor = listCaptor();
        verify(connectionGate, times(1)).releaseActiveTargets(captor.capture(), anyLong());
        assertEquals(List.of("eqpA"), captor.getValue());
    }

    @Test
    @DisplayName("AC3: FAILED 이면 release 1회 + 추적 제거(다음 tick 은 release 0회)")
    void reaperReleasesAndUntracksOnFailed() {
        registry.track("RUN-1", Set.of("eqpA"));
        when(client.getRun("RUN-1")).thenReturn(run("RUN-1", "FAILED"));

        reaper.reapTerminatedRuns();
        reaper.reapTerminatedRuns(); // 두 번째 tick: 이미 제거되어 추가 release 없음

        verify(connectionGate, times(1)).releaseActiveTargets(any(), anyLong());
        assertTrue(registry.trackedRunIds().isEmpty(), "FAILED 정리 후 추적에서 제거되어야 한다");
    }

    @Test
    @DisplayName("AC4: CANCELED 이면 release 를 호출하지 않는다(stop 전담 — race 회피)")
    void reaperSkipsCanceled() {
        registry.track("RUN-1", Set.of("eqpA"));
        when(client.getRun("RUN-1")).thenReturn(run("RUN-1", "CANCELED"));

        reaper.reapTerminatedRuns();

        verify(connectionGate, never()).releaseActiveTargets(any(), anyLong());
        assertTrue(registry.trackedRunIds().contains("RUN-1"), "CANCELED 는 종료로 보지 않아 추적 유지");
    }

    @Test
    @DisplayName("AC8: getRun 404(SimulatorRunNotFoundException)이면 종료로 간주 → release 1회 + 제거")
    void reaperTreats404AsTerminated() {
        registry.track("RUN-1", Set.of("eqpA"));
        when(client.getRun("RUN-1")).thenThrow(new SimulatorRunNotFoundException("run 미존재"));

        reaper.reapTerminatedRuns();

        verify(connectionGate, times(1)).releaseActiveTargets(any(), anyLong());
        assertTrue(registry.trackedRunIds().isEmpty(), "404 정리 후 추적에서 제거되어야 한다");
    }

    @Test
    @DisplayName("AC9: getRun 5xx(IllegalStateException)이면 일시 오류 → release 0회 + 추적 유지")
    void reaperKeepsTrackingOnServerError() {
        registry.track("RUN-1", Set.of("eqpA"));
        when(client.getRun("RUN-1")).thenThrow(new IllegalStateException("5xx"));

        reaper.reapTerminatedRuns();

        verify(connectionGate, never()).releaseActiveTargets(any(), anyLong());
        assertFalse(registry.trackedRunIds().isEmpty(), "일시 오류는 추적을 유지해 다음 tick 재시도");
    }

    @Test
    @DisplayName("AC10: getRun IO/타임아웃(UiBadRequestException, 404 아님)이면 일시 오류 → release 0회 + 추적 유지")
    void reaperKeepsTrackingOnIoError() {
        registry.track("RUN-1", Set.of("eqpA"));
        when(client.getRun("RUN-1")).thenThrow(new UiBadRequestException("IO 오류"));

        reaper.reapTerminatedRuns();

        verify(connectionGate, never()).releaseActiveTargets(any(), anyLong());
        assertFalse(registry.trackedRunIds().isEmpty(), "일시 오류는 추적을 유지해 다음 tick 재시도");
    }

    @Test
    @DisplayName("M10: per-target 부분 해제 후 TERMINAL 이면 reaper 가 잔여 captured 만 END + 추적 제거(분담)")
    void reaperReleasesRemainingCapturedAfterPartialRelease() {
        registry.track("RUN-1", Set.of("eqpA", "eqpB"));
        // per-target 종료로 eqpA 만 부분 해제(facade.stopTarget 의 release 경로) — 키는 유지, {eqpB} 잔존.
        assertTrue(registry.release("RUN-1", "eqpA"));
        when(client.getRun("RUN-1")).thenReturn(run("RUN-1", "TERMINAL"));

        reaper.reapTerminatedRuns();

        final ArgumentCaptor<List<String>> captor = listCaptor();
        verify(connectionGate, times(1)).releaseActiveTargets(captor.capture(), anyLong());
        assertEquals(List.of("eqpB"), captor.getValue(), "부분 해제된 eqpA 제외, 잔여 eqpB 만 END(이중 END 0)");
        assertTrue(registry.trackedRunIds().isEmpty(), "TERMINAL 정리 후 추적 제거");
    }

    private static SimulatorRunResponse run(final String runId, final String state) {
        return new SimulatorRunResponse(runId, "ALL", state, List.of());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<String>> listCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }
}
