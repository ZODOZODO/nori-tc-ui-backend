package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.eqp.EqpCommandResult;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.eqp.StartOutcome;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.model.AsyncResultEntry;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.port.db.EqpManageQueryPort;
import com.nori.tc.ui.core.port.db.EqpOptionsQueryPort;
import com.nori.tc.ui.core.port.redis.AsyncResultStorePort;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.task.UiTaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EqpManagementService#awaitAsyncResult} 의 PASS/FAIL/TIMEOUT 환원을 검증합니다.
 */
class EqpManagementServiceTest {

    private final AsyncResultStorePort asyncResultStorePort = mock(AsyncResultStorePort.class);
    private final EqpManagementService service = new EqpManagementService(
            mock(EqpManageQueryPort.class),
            mock(EqpOptionsQueryPort.class),
            mock(EqpDirectScenarioService.class),
            asyncResultStorePort,
            mock(com.nori.tc.ui.core.coordination.UiEqpMutationCoordinator.class),
            mock(Executor.class)
    );

    /**
     * §13.2 — mutation 은 <b>접수</b>로 끝난다. 종전에는 컨트롤러가 넘긴 timeout 만큼 실행을 기다렸고,
     * 그 시간을 넘기면 사용자에게 실패로 보이는데 백그라운드 작업은 계속 진행돼 응답과 실제 결과가
     * 어긋났다. 이제는 실행을 기다리지 않으므로 "요청이 오래 걸리면 실패" 라는 개념 자체가 없다.
     */
    @Test
    @DisplayName("§13.2: update 는 실행을 기다리지 않고 접수 결과를 즉시 반환합니다")
    void updateReturnsAcceptanceWithoutWaitingForExecution() {
        final com.nori.tc.ui.core.coordination.UiEqpMutationCoordinator slowCoordinator =
                mock(com.nori.tc.ui.core.coordination.UiEqpMutationCoordinator.class);
        final com.nori.tc.ui.core.coordination.model.UiOperationAcceptance accepted =
                com.nori.tc.ui.core.coordination.model.UiOperationAcceptance.accepted("op-1");
        when(slowCoordinator.update(eq("EQP-01"), any(), eq("KEY-1"))).thenReturn(accepted);

        final EqpManagementService acceptingService = new EqpManagementService(
                mock(EqpManageQueryPort.class),
                mock(EqpOptionsQueryPort.class),
                mock(EqpDirectScenarioService.class),
                asyncResultStorePort,
                slowCoordinator,
                Executors.newSingleThreadExecutor()
        );

        final var result = acceptingService.acceptUpdate(
                "EQP-01", mock(EqpManagementCommand.Update.class), "KEY-1");

        assertEquals("op-1", result.operationId());
        assertEquals(com.nori.tc.ui.core.coordination.model.UiOperationState.PENDING, result.state());
        assertEquals(com.nori.tc.ui.core.coordination.model.UiOperationPhase.ACCEPTED, result.phase());
    }

    /**
     * S08 D-10 — {@code runAsync} 람다 본문 예외를 받는 exceptionally/whenComplete 가 없어
     * 로그 한 줄 없이 소실됐다. Redis 장애로 markCompleted 가 던지면 traceId 는 PENDING 에 남고
     * 스케줄러가 뒤늦게 TIMEOUT 으로 승격시켜 성공한 명령이 "타임아웃" 으로 보고된다.
     */
    @Test
    @DisplayName("S08 D-10: 비동기 본문에서 markCompleted 가 실패하면 TIMEOUT 으로 확정합니다")
    void asyncBodyFailureIsMarkedTimeoutInsteadOfBeingSwallowed() throws Exception {
        final AsyncResultStorePort failingStore = mock(AsyncResultStorePort.class);
        doThrow(new IllegalStateException("Redis 장애 주입"))
                .when(failingStore).markCompleted(any(), any());
        final EqpDirectScenarioService directService = mock(EqpDirectScenarioService.class);
        when(directService.executeStart(eq("EQP-01"), eq("SOCKET"), any(), any()))
                .thenReturn(EqpCommandResult.ok());
        final EqpManagementService failingService = new EqpManagementService(
                mock(EqpManageQueryPort.class),
                mock(EqpOptionsQueryPort.class),
                directService,
                failingStore,
                mock(com.nori.tc.ui.core.coordination.UiEqpMutationCoordinator.class),
                Executors.newSingleThreadExecutor()
        );

        final var accepted = failingService.startAsync("EQP-01", "SOCKET", null, 1_000L);

        // 람다 본문 실패가 삼켜지면 PENDING 이 남아 스케줄러가 뒤늦게 TIMEOUT 으로 승격시킨다.
        // 실패 시점에 즉시 TIMEOUT 으로 확정해야 관측 가능하다.
        verify(failingStore, timeout(3_000L)).markTimeout(accepted.traceId());
    }

    @Test
    @DisplayName("COMPLETED + reply PASS 면 awaitAsyncResult 는 PASS 를 반환합니다")
    void awaitReturnsPassOnCompletedSuccess() {
        final UiCommandReply reply = new UiCommandReply(
                "trace-1", "TC-UI-APP", "EQP_START_REP", "EQP-01", "SOCKET", UiTaskStatus.PASS, null, null);
        when(asyncResultStorePort.getWithStatus("trace-1"))
                .thenReturn(Optional.of(AsyncResultEntry.completed("trace-1", reply)));

        final StartOutcome outcome = service.awaitAsyncResult("trace-1", 1_000L);

        assertTrue(outcome.isPass());
        assertEquals(StartOutcome.State.PASS, outcome.state());
    }

    @Test
    @DisplayName("COMPLETED + reply FAIL 이면 errorCode 를 운반한 FAIL 을 반환합니다")
    void awaitReturnsFailOnCompletedFailure() {
        final UiCommandReply reply = new UiCommandReply(
                "trace-2", "TC-UI-APP", "EQP_START_REP", "EQP-01", "SOCKET",
                UiTaskStatus.FAIL, "EQP_NOT_CONNECTED", "gateway 연결 실패");
        when(asyncResultStorePort.getWithStatus("trace-2"))
                .thenReturn(Optional.of(AsyncResultEntry.completed("trace-2", reply)));

        final StartOutcome outcome = service.awaitAsyncResult("trace-2", 1_000L);

        assertFalse(outcome.isPass());
        assertEquals(StartOutcome.State.FAIL, outcome.state());
        assertEquals("EQP_NOT_CONNECTED", outcome.errorCode());
        assertEquals("gateway 연결 실패", outcome.errorMessage());
    }

    @Test
    @DisplayName("저장소 상태가 TIMEOUT 이면 TIMEOUT 을 반환합니다")
    void awaitReturnsTimeoutOnTimeoutStatus() {
        when(asyncResultStorePort.getWithStatus("trace-3"))
                .thenReturn(Optional.of(AsyncResultEntry.timeout("trace-3")));

        final StartOutcome outcome = service.awaitAsyncResult("trace-3", 1_000L);

        assertEquals(StartOutcome.State.TIMEOUT, outcome.state());
    }

    @Test
    @DisplayName("deadline 내 종료 상태에 도달하지 못하면(PENDING 유지) TIMEOUT 을 반환합니다")
    void awaitReturnsTimeoutWhenDeadlineExceeded() {
        when(asyncResultStorePort.getWithStatus("trace-4"))
                .thenReturn(Optional.of(AsyncResultEntry.pending("trace-4", System.currentTimeMillis() + 60_000L)));

        // timeoutMs=0 → 첫 조회에서 PENDING 확인 후 즉시 deadline 초과 → TIMEOUT (실대기 회피)
        final StartOutcome outcome = service.awaitAsyncResult("trace-4", 0L);

        assertEquals(StartOutcome.State.TIMEOUT, outcome.state());
    }

    /**
     * PLC 폴링 그룹 명령은 요청 본문이 없어 interfaceType 을 운반하지 않는다 — 서비스가 스냅샷에서
     * 직접 해결해야 한다.
     *
     * <p>해결하지 않고 null 을 넘기면 {@code UiCommandReply} 의 필수값 검증이 비동기 본문에서 터지고,
     * {@code whenComplete} 가 이를 TIMEOUT 으로 확정한다. 그러면 gateway 가 정상 수락하고
     * {@code enabled_poll_groups} 변이까지 끝난 명령이 UI 에는 타임아웃 실패로 보고된다.
     * PASS/FAIL 양쪽 경로가 모두 같은 지점을 지나므로 <b>모든 결과가</b> TIMEOUT 이 된다.</p>
     */
    @Test
    @DisplayName("PLC 폴링 그룹 시작은 스냅샷의 interfaceType 을 실어 PASS 로 확정합니다")
    void startPollingGroupResolvesInterfaceTypeFromSnapshot() {
        final EqpManageQueryPort queryPort = mock(EqpManageQueryPort.class);
        when(queryPort.findManageSnapshotByEqpId("EQP-PLC-01")).thenReturn(Optional.of(plcSnapshot()));
        final EqpDirectScenarioService directService = mock(EqpDirectScenarioService.class);
        when(directService.executeGroupStart("EQP-PLC-01", "EQP_STATUS_REPORT", "SYSTEM"))
                .thenReturn(EqpCommandResult.ok());
        final EqpManagementService groupService = new EqpManagementService(
                queryPort,
                mock(EqpOptionsQueryPort.class),
                directService,
                asyncResultStorePort,
                mock(com.nori.tc.ui.core.coordination.UiEqpMutationCoordinator.class),
                Executors.newSingleThreadExecutor()
        );

        final var accepted = groupService.startPollingGroupAsync("EQP-PLC-01", "EQP_STATUS_REPORT", 5_000L);

        final ArgumentCaptor<UiCommandReply> replyCaptor = ArgumentCaptor.forClass(UiCommandReply.class);
        verify(asyncResultStorePort, timeout(3_000L)).markCompleted(eq(accepted.traceId()), replyCaptor.capture());
        assertEquals(UiTaskStatus.PASS, replyCaptor.getValue().status());
        assertEquals("PLC", replyCaptor.getValue().interfaceType());
        assertEquals("EQP_PLC_GROUP_START_REP", replyCaptor.getValue().eventType());
        verify(asyncResultStorePort, never()).markTimeout(any());
    }

    /**
     * 실패 결과도 FAIL 로 환원돼야 합니다 — 실패를 타임아웃으로 보고하면 운영자가 원인을 알 수 없습니다.
     */
    @Test
    @DisplayName("PLC 폴링 그룹 중단이 실패하면 TIMEOUT 이 아니라 FAIL 로 확정합니다")
    void endPollingGroupReportsFailureAsFail() {
        final EqpManageQueryPort queryPort = mock(EqpManageQueryPort.class);
        when(queryPort.findManageSnapshotByEqpId("EQP-PLC-01")).thenReturn(Optional.of(plcSnapshot()));
        final EqpDirectScenarioService directService = mock(EqpDirectScenarioService.class);
        when(directService.executeGroupEnd("EQP-PLC-01", "EQP_STATUS_REPORT", "SYSTEM"))
                .thenReturn(EqpCommandResult.error(409, "EQP_NOT_CONNECTED", "gateway 미연결"));
        final EqpManagementService groupService = new EqpManagementService(
                queryPort,
                mock(EqpOptionsQueryPort.class),
                directService,
                asyncResultStorePort,
                mock(com.nori.tc.ui.core.coordination.UiEqpMutationCoordinator.class),
                Executors.newSingleThreadExecutor()
        );

        final var accepted = groupService.endPollingGroupAsync("EQP-PLC-01", "EQP_STATUS_REPORT", 5_000L);

        final ArgumentCaptor<UiCommandReply> replyCaptor = ArgumentCaptor.forClass(UiCommandReply.class);
        verify(asyncResultStorePort, timeout(3_000L)).markCompleted(eq(accepted.traceId()), replyCaptor.capture());
        assertEquals(UiTaskStatus.FAIL, replyCaptor.getValue().status());
        assertEquals("EQP_NOT_CONNECTED", replyCaptor.getValue().errorCode());
        verify(asyncResultStorePort, never()).markTimeout(any());
    }

    /**
     * 없는 설비는 202 후 타임아웃이 아니라 즉시 미존재로 끊어야 합니다.
     */
    @Test
    @DisplayName("존재하지 않는 설비의 폴링 그룹 명령은 UiNotFoundException 입니다")
    void pollingGroupRejectsUnknownEqp() {
        final EqpManageQueryPort queryPort = mock(EqpManageQueryPort.class);
        when(queryPort.findManageSnapshotByEqpId("EQP-NONE")).thenReturn(Optional.empty());
        final EqpManagementService groupService = new EqpManagementService(
                queryPort,
                mock(EqpOptionsQueryPort.class),
                mock(EqpDirectScenarioService.class),
                asyncResultStorePort,
                mock(com.nori.tc.ui.core.coordination.UiEqpMutationCoordinator.class),
                mock(Executor.class)
        );

        assertThrows(UiNotFoundException.class,
                () -> groupService.startPollingGroupAsync("EQP-NONE", "EQP_STATUS_REPORT", 1_000L));
    }

    private static EqpManagementSnapshot plcSnapshot() {
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        final UiEqp eqp = new UiEqp(
                7L, "EQP-PLC-01", 1L, UiProtocolType.PLC, "ACTIVE", false, 0,
                "10.30.0.1", 502, 1L, null, null, null, true, now, now, "SYSTEM", "SYSTEM"
        );
        return new EqpManagementSnapshot(
                eqp, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), null, null
        );
    }
}
