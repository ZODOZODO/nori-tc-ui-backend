package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationPhase;
import com.nori.tc.ui.core.coordination.model.UiOperationState;
import com.nori.tc.ui.core.coordination.model.UiOperationStatusView;
import com.nori.tc.ui.core.coordination.model.UiOperationView;
import com.nori.tc.ui.core.coordination.model.UiResourceHeadView;
import com.nori.tc.ui.core.coordination.port.UiOperationLedgerPort;
import com.nori.tc.ui.core.eqp.EqpCommandResult;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.model.AsyncResultEntry;
import com.nori.tc.ui.core.model.UiCommandCoordination;
import com.nori.tc.ui.core.model.UiCommandMessage;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.port.redis.AsyncResultStorePort;
import com.nori.tc.ui.core.service.EqpDirectScenarioService;
import com.nori.tc.ui.domain.task.UiTaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §13.3 조회 계약과 명령/응답 계약 모델의 불변식을 고정합니다.
 */
class UiOperationQueryAndContractTest {

    // ------------------------------------------------------------------ 조회

    @Test
    @DisplayName("원장에 있으면 원장 상태를 그대로 돌려준다")
    void ledgerOperationIsReturned() {
        final UiOperationQueryService service = queryService(operation(UiOperationState.PASS,
                UiOperationPhase.BARRIER_OPEN, 1_000L), null);

        final UiOperationStatusView view = service.find("op-1", 1_500L).orElseThrow();
        assertEquals(UiOperationState.PASS, view.status());
        assertEquals(UiOperationPhase.BARRIER_OPEN, view.phase());
        assertTrue(view.terminal());
    }

    @Test
    @DisplayName("terminal operation 은 deadline 이 지나도 RECONCILING 으로 바뀌지 않는다")
    void terminalOperationKeepsItsPhase() {
        final UiOperationQueryService service = queryService(operation(UiOperationState.FAIL,
                UiOperationPhase.ABORTED, 1L), null);

        assertEquals(UiOperationPhase.ABORTED, service.find("op-1", 9_000_000L).orElseThrow().phase());
    }

    @Test
    @DisplayName("레거시 PENDING 엔트리는 PENDING/ACCEPTED 로 환원된다")
    void legacyPendingIsMapped() {
        final UiOperationQueryService service =
                queryService(null, AsyncResultEntry.pending("trace-1", 9_000_000L));

        final UiOperationStatusView view = service.find("trace-1", 1_000L).orElseThrow();
        assertEquals(UiOperationState.PENDING, view.status());
        assertEquals(UiOperationPhase.ACCEPTED, view.phase());
        assertFalse(view.terminal());
        assertNull(view.eqpId());
    }

    @Test
    @DisplayName("어느 쪽에도 없으면 빈 결과다 — 404 로 표현할 근거는 이 하나뿐이다")
    void unknownIdIsEmpty() {
        assertTrue(queryService(null, null).find("nope", 1_000L).isEmpty());
    }

    // --------------------------------------------------------------- 명령 계약

    @Test
    @DisplayName("v1 명령에는 coordination metadata 가 붙지 않는다")
    void v1CommandHasNoCoordination() {
        final UiCommandMessage v1 = new UiCommandMessage(
                UiCommandEventType.EQP_START, "trace", "TC-UI-APP", "EQP001", "SOCKET", null, null);

        assertNull(v1.coordination());
    }

    @Test
    @DisplayName("coordination metadata 를 덧붙여도 나머지 필드는 그대로다")
    void withCoordinationPreservesOtherFields() {
        final UiCommandCoordination coordination = new UiCommandCoordination(
                "op-1", "exec-1", UiCommandCoordination.PHASE_PREPARE, 0L, 7L,
                "a".repeat(64), List.of("equipment:EQP001"), "2026-08-25T00:00:00Z");
        final UiCommandMessage v2 = new UiCommandMessage(
                UiCommandEventType.EQP_UPDATE, "trace", "TC-UI-APP", "EQP001", "SOCKET", null, null)
                .withCoordination(coordination);

        assertEquals("trace", v2.traceId());
        assertEquals("EQP001", v2.eqpId());
        assertEquals(coordination, v2.coordination());
        assertEquals(List.of("equipment:EQP001"), v2.coordination().resourceKeys());
    }

    @Test
    @DisplayName("coordination metadata 는 operationId/phase/digest/revision 없이는 만들 수 없다")
    void coordinationRequiresProofFields() {
        assertThrows(NullPointerException.class, () -> new UiCommandCoordination(
                null, null, UiCommandCoordination.PHASE_APPLY, null, 7L, "a".repeat(64), List.of(), null));
        assertThrows(NullPointerException.class, () -> new UiCommandCoordination(
                "op-1", null, UiCommandCoordination.PHASE_APPLY, null, null, "a".repeat(64), List.of(), null));
    }

    @Test
    @DisplayName("resource head 는 claim/차단이 없고 revision 이 같을 때만 새 mutation 을 받는다")
    void resourceHeadFreeCondition() {
        assertTrue(new UiResourceHeadView("k", 3L, 3L, null, null).freeForNewOperation());
        assertFalse(new UiResourceHeadView("k", 3L, 3L, "op-x", null).freeForNewOperation());
        assertFalse(new UiResourceHeadView("k", 3L, 3L, null, "op-y").freeForNewOperation());
        assertFalse(new UiResourceHeadView("k", 4L, 3L, null, null).freeForNewOperation());
    }

    @Test
    @DisplayName("비조정 구성은 실행 결과를 그대로 접수 응답으로 환원한다")
    void directCoordinatorReturnsTerminalAcceptance() {
        final EqpDirectScenarioService directService = mock(EqpDirectScenarioService.class);
        when(directService.executeDelete("EQP001")).thenReturn(EqpCommandResult.ok());
        when(directService.executeCreate(any())).thenReturn(
                new EqpCommandResult(false, 409, "CONFLICT", "충돌"));

        final DirectUiEqpMutationCoordinator coordinator = new DirectUiEqpMutationCoordinator(directService);
        assertTrue(coordinator.appliesImmediately());

        final var ok = coordinator.delete("EQP001", "KEY-1");
        assertEquals(UiOperationState.PASS, ok.state());
        assertEquals(UiOperationPhase.BARRIER_OPEN, ok.phase());

        final var failed = coordinator.create(mock(EqpManagementCommand.Create.class), "KEY-2");
        assertEquals(UiOperationState.FAIL, failed.state());
        assertEquals("CONFLICT", failed.errorCode());
    }

    @Test
    @DisplayName("조정 비활성 상태 표면은 항상 Ready 로 보고한다")
    void disabledStatusIsAlwaysReady() {
        final UiCoordinationStatus disabled = UiCoordinationStatus.disabled();

        assertFalse(disabled.coordinationEnabled());
        assertTrue(disabled.readyForMutation());
        assertEquals(false, disabled.details().get("coordinationEnabled"));
        assertEquals(com.nori.tc.ui.core.coordination.model.UiMembershipState.ACTIVE_SYNCED,
                disabled.membershipState());
    }

    @Test
    @DisplayName("조정 불가 예외는 503 코드를 고정한다")
    void coordinationUnavailableCarriesItsErrorCode() {
        final UiCoordinationUnavailableException ex =
                new UiCoordinationUnavailableException("원장 접근 실패", new IllegalStateException("cause"));

        assertEquals("COORDINATION_UNAVAILABLE", UiCoordinationUnavailableException.ERROR_CODE);
        assertEquals("원장 접근 실패", ex.getMessage());
    }

    // ------------------------------------------------------------------ fixture

    private static UiOperationQueryService queryService(
            final UiOperationView operation,
            final AsyncResultEntry legacyEntry
    ) {
        final UiOperationLedgerPort ledgerPort = mock(UiOperationLedgerPort.class);
        when(ledgerPort.readOperation(anyString())).thenReturn(Optional.ofNullable(operation));
        final AsyncResultStorePort storePort = mock(AsyncResultStorePort.class);
        when(storePort.getWithStatus(anyString())).thenReturn(Optional.ofNullable(legacyEntry));
        return new UiOperationQueryService(ledgerPort, storePort, new UiCoordinationProperties());
    }

    private static UiOperationView operation(
            final UiOperationState state,
            final UiOperationPhase phase,
            final long updatedAt
    ) {
        return new UiOperationView(
                "op-1", null, "EQP001", "EQP_UPDATE", state, phase,
                List.of("equipment:EQP001"), List.of(), "a".repeat(64), 0L, 7L, false, null, null,
                null, null, null, updatedAt, updatedAt, state == UiOperationState.PENDING ? null : updatedAt);
    }
}
