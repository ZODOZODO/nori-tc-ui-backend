package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.eqp.EqpAsyncAcceptResult;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.eqp.StartOutcome;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.domain.common.eqp.UiControlState;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpRuntimeState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DemoRunConnectionGate} 의 ACTIVE 연결 보장(AC7)/해제(AC8) 라이프사이클을 검증합니다.
 *
 * <p>EqpManagementService 를 mock 하여 EQP_START/END 발행 + PASS/FAIL/TIMEOUT 관측 + 분기까지 검증합니다.
 * 실제 desired_state DB 전이는 단일-writer(EQP_START/END 시나리오)가 수행하므로 e2e(AC9) 범위입니다.</p>
 */
class DemoRunConnectionGateTest {

    private final EqpManagementService eqpManagementService = mock(EqpManagementService.class);
    private final DemoRunConnectionGate gate = new DemoRunConnectionGate(eqpManagementService);

    @Test
    @DisplayName("AC7: ENDED ACTIVE 대상만 EQP_START 발행하고 PASS 확인 후 started 집합을 반환한다(PASSIVE/이미-STARTED skip)")
    void ensureStartsOnlyEndedActiveTargets() {
        when(eqpManagementService.getManageDetail("eqpA")).thenReturn(Optional.of(snapshot("eqpA", "ACTIVE", null)));
        when(eqpManagementService.getManageDetail("eqpB")).thenReturn(Optional.of(snapshot("eqpB", "PASSIVE", null)));
        when(eqpManagementService.getManageDetail("eqpC")).thenReturn(Optional.of(snapshot("eqpC", "ACTIVE", "STARTED")));
        when(eqpManagementService.startAsync(eq("eqpA"), eq("SOCKET"), isNull(), anyLong()))
                .thenReturn(EqpAsyncAcceptResult.accepted("trace-A"));
        when(eqpManagementService.awaitAsyncResult(eq("trace-A"), anyLong())).thenReturn(StartOutcome.pass());

        final Set<String> started = gate.ensureActiveTargetsConnected(List.of("eqpA", "eqpB", "eqpC"), 10_000L);

        assertEquals(Set.of("eqpA"), started);
        verify(eqpManagementService).startAsync(eq("eqpA"), eq("SOCKET"), isNull(), anyLong());
        // PASSIVE(eqpB)·이미 STARTED(eqpC)는 EQP_START 발행하지 않는다.
        verify(eqpManagementService, never()).startAsync(eq("eqpB"), any(), any(), anyLong());
        verify(eqpManagementService, never()).startAsync(eq("eqpC"), any(), any(), anyLong());
    }

    @Test
    @DisplayName("AC7: 시작할 ACTIVE 대상이 없으면(전부 PASSIVE/STARTED) EQP_START 를 발행하지 않고 빈 집합을 반환한다")
    void ensureNoOpWhenAllSkipped() {
        when(eqpManagementService.getManageDetail("eqpB")).thenReturn(Optional.of(snapshot("eqpB", "PASSIVE", null)));
        when(eqpManagementService.getManageDetail("eqpC")).thenReturn(Optional.of(snapshot("eqpC", "ACTIVE", "STARTED")));

        final Set<String> started = gate.ensureActiveTargetsConnected(List.of("eqpB", "eqpC"), 10_000L);

        assertEquals(Set.of(), started);
        verify(eqpManagementService, never()).startAsync(anyString(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("AC7-neg: 한 대상이 FAIL 이면 이번 호출이 START 한 전체에 보상 EQP_END 후 예외를 던진다(조용한 부분 시작 금지)")
    void ensureRollsBackAndThrowsOnFailure() {
        when(eqpManagementService.getManageDetail("eqpA")).thenReturn(Optional.of(snapshot("eqpA", "ACTIVE", null)));
        when(eqpManagementService.getManageDetail("eqpD")).thenReturn(Optional.of(snapshot("eqpD", "ACTIVE", null)));
        when(eqpManagementService.startAsync(eq("eqpA"), eq("SOCKET"), isNull(), anyLong()))
                .thenReturn(EqpAsyncAcceptResult.accepted("trace-A"));
        when(eqpManagementService.startAsync(eq("eqpD"), eq("SOCKET"), isNull(), anyLong()))
                .thenReturn(EqpAsyncAcceptResult.accepted("trace-D"));
        when(eqpManagementService.awaitAsyncResult(eq("trace-A"), anyLong())).thenReturn(StartOutcome.pass());
        when(eqpManagementService.awaitAsyncResult(eq("trace-D"), anyLong()))
                .thenReturn(StartOutcome.fail("EQP_NOT_CONNECTED", "gateway 연결 실패"));
        when(eqpManagementService.endAsync(anyString(), anyString(), isNull(), anyLong()))
                .thenReturn(EqpAsyncAcceptResult.error(500, "X", "Y"));

        assertThrows(UiConflictException.class,
                () -> gate.ensureActiveTargetsConnected(List.of("eqpA", "eqpD"), 10_000L));

        // 보상 EQP_END 는 이번 호출이 START 발행한 두 설비 모두에 발행되어야 한다.
        verify(eqpManagementService).endAsync(eq("eqpA"), eq("SOCKET"), isNull(), anyLong());
        verify(eqpManagementService).endAsync(eq("eqpD"), eq("SOCKET"), isNull(), anyLong());
    }

    @Test
    @DisplayName("AC7-neg: TIMEOUT 도 FAIL 과 동일하게 보상 END 후 예외를 던진다")
    void ensureRollsBackAndThrowsOnTimeout() {
        when(eqpManagementService.getManageDetail("eqpA")).thenReturn(Optional.of(snapshot("eqpA", "ACTIVE", null)));
        when(eqpManagementService.startAsync(eq("eqpA"), eq("SOCKET"), isNull(), anyLong()))
                .thenReturn(EqpAsyncAcceptResult.accepted("trace-A"));
        when(eqpManagementService.awaitAsyncResult(eq("trace-A"), anyLong())).thenReturn(StartOutcome.timeout());
        when(eqpManagementService.endAsync(anyString(), anyString(), isNull(), anyLong()))
                .thenReturn(EqpAsyncAcceptResult.error(500, "X", "Y"));

        assertThrows(UiConflictException.class,
                () -> gate.ensureActiveTargetsConnected(List.of("eqpA"), 10_000L));

        verify(eqpManagementService).endAsync(eq("eqpA"), eq("SOCKET"), isNull(), anyLong());
    }

    @Test
    @DisplayName("AC7-neg: EQP_START 발행 자체가 실패하면(accepted=false) 즉시 거부하고 await 하지 않는다")
    void ensureRejectsOnPublishFailure() {
        when(eqpManagementService.getManageDetail("eqpA")).thenReturn(Optional.of(snapshot("eqpA", "ACTIVE", null)));
        when(eqpManagementService.startAsync(eq("eqpA"), eq("SOCKET"), isNull(), anyLong()))
                .thenReturn(EqpAsyncAcceptResult.error(500, "PUBLISH_FAILED", "발행 실패"));

        assertThrows(UiConflictException.class,
                () -> gate.ensureActiveTargetsConnected(List.of("eqpA"), 10_000L));

        verify(eqpManagementService, never()).awaitAsyncResult(anyString(), anyLong());
    }

    @Test
    @DisplayName("AC8: stop 시 ACTIVE STARTED 대상에만 EQP_END 를 발행한다(PASSIVE/STARTED 아님 skip)")
    void releaseEndsOnlyActiveStartedTargets() {
        when(eqpManagementService.getManageDetail("eqpA")).thenReturn(Optional.of(snapshot("eqpA", "ACTIVE", "STARTED")));
        when(eqpManagementService.getManageDetail("eqpB")).thenReturn(Optional.of(snapshot("eqpB", "PASSIVE", "STARTED")));
        when(eqpManagementService.getManageDetail("eqpC")).thenReturn(Optional.of(snapshot("eqpC", "ACTIVE", null)));
        when(eqpManagementService.endAsync(eq("eqpA"), eq("SOCKET"), isNull(), anyLong()))
                .thenReturn(EqpAsyncAcceptResult.accepted("end-A"));
        when(eqpManagementService.awaitAsyncResult(eq("end-A"), anyLong())).thenReturn(StartOutcome.pass());

        gate.releaseActiveTargets(List.of("eqpA", "eqpB", "eqpC"), 10_000L);

        verify(eqpManagementService).endAsync(eq("eqpA"), eq("SOCKET"), isNull(), anyLong());
        verify(eqpManagementService, never()).endAsync(eq("eqpB"), any(), any(), anyLong());
        verify(eqpManagementService, never()).endAsync(eq("eqpC"), any(), any(), anyLong());
    }

    @Test
    @DisplayName("AC8: release 는 best-effort — getManageDetail 이 실패해도 예외를 전파하지 않는다")
    void releaseIsBestEffortAndNeverThrows() {
        when(eqpManagementService.getManageDetail("eqpX")).thenThrow(new RuntimeException("boom"));

        assertDoesNotThrow(() -> gate.releaseActiveTargets(List.of("eqpX"), 10_000L));
    }

    @Test
    @DisplayName("배리어: PASSIVE 대상이 control_state=REMOTE 면 반환하고, ACTIVE 는 폴링 대상에서 제외한다")
    void awaitPassiveReturnsWhenPassiveConnectedAndSkipsActive() {
        when(eqpManagementService.getManageDetail("eqpA")).thenReturn(Optional.of(snapshot("eqpA", "ACTIVE", "STARTED")));
        when(eqpManagementService.getManageDetail("eqpB"))
                .thenReturn(Optional.of(snapshot("eqpB", "PASSIVE", "STARTED", UiControlState.REMOTE)));

        assertDoesNotThrow(() -> gate.awaitPassiveTargetsConnected(List.of("eqpA", "eqpB"), 1_000L));
    }

    @Test
    @DisplayName("배리어-neg: PASSIVE 대상이 시간 내 control_state=REMOTE/LOCAL 미도달이면 예외를 던진다(MES 활성화 차단)")
    void awaitPassiveThrowsWhenPassiveNeverConnects() {
        when(eqpManagementService.getManageDetail("eqpB"))
                .thenReturn(Optional.of(snapshot("eqpB", "PASSIVE", "STARTED", UiControlState.OFFLINE)));

        assertThrows(UiConflictException.class,
                () -> gate.awaitPassiveTargetsConnected(List.of("eqpB"), 100L));
    }

    @Test
    @DisplayName("배리어: PASSIVE 대상이 없으면(전부 ACTIVE) 폴링 없이 즉시 반환한다")
    void awaitPassiveNoOpWhenNoPassiveTargets() {
        when(eqpManagementService.getManageDetail("eqpA")).thenReturn(Optional.of(snapshot("eqpA", "ACTIVE", "STARTED")));

        assertDoesNotThrow(() -> gate.awaitPassiveTargetsConnected(List.of("eqpA"), 1_000L));
    }

    @Test
    @DisplayName("UPSTREAM_ONLY: ACTIVE 미시작 대상에만 EQP_START 를 best-effort 발행하고 PASS 를 대기하지 않는다(PASSIVE/STARTED skip)")
    void startBestEffortIssuesStartForActiveOnlyWithoutAwait() {
        when(eqpManagementService.getManageDetail("eqpA")).thenReturn(Optional.of(snapshot("eqpA", "ACTIVE", null)));
        when(eqpManagementService.getManageDetail("eqpB")).thenReturn(Optional.of(snapshot("eqpB", "PASSIVE", null)));
        when(eqpManagementService.getManageDetail("eqpC")).thenReturn(Optional.of(snapshot("eqpC", "ACTIVE", "STARTED")));
        when(eqpManagementService.startAsync(eq("eqpA"), eq("SOCKET"), isNull(), anyLong()))
                .thenReturn(EqpAsyncAcceptResult.accepted("trace-A"));

        gate.startActiveTargetsBestEffort(List.of("eqpA", "eqpB", "eqpC"), 10_000L);

        verify(eqpManagementService).startAsync(eq("eqpA"), eq("SOCKET"), isNull(), anyLong());
        verify(eqpManagementService, never()).startAsync(eq("eqpB"), any(), any(), anyLong());
        verify(eqpManagementService, never()).startAsync(eq("eqpC"), any(), any(), anyLong());
        // best-effort — 연결 PASS 를 대기하지 않는다(실설비 대상이라 데모 환경에서 연결 미보장).
        verify(eqpManagementService, never()).awaitAsyncResult(anyString(), anyLong());
    }

    private static EqpManagementSnapshot snapshot(final String eqpId, final String commMode, final String desiredState) {
        return snapshot(eqpId, commMode, desiredState, null);
    }

    private static EqpManagementSnapshot snapshot(
            final String eqpId, final String commMode, final String desiredState, final UiControlState controlState) {
        final UiEqpRuntimeState runtimeState = desiredState == null
                ? null
                : new UiEqpRuntimeState(1L, controlState, null, desiredState, null, null, null, null);
        return new EqpManagementSnapshot(
                new UiEqp(1L, eqpId, 1L, UiProtocolType.SOCKET, commMode, false, 0, "127.0.0.1", 6101, 1L,
                        "EDIT", null, null, true, OffsetDateTime.now(), OffsetDateTime.now(), "SYSTEM", "SYSTEM"),
                null,
                null,
                null,
                null,
                null,
                runtimeState,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null
        );
    }
}
