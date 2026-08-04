package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.eqp.EqpCommandResult;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.StartOutcome;
import com.nori.tc.ui.core.model.AsyncResultEntry;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.port.db.EqpManageQueryPort;
import com.nori.tc.ui.core.port.db.EqpOptionsQueryPort;
import com.nori.tc.ui.core.port.redis.AsyncResultStorePort;
import com.nori.tc.ui.domain.task.UiTaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
            mock(Executor.class)
    );

    /**
     * S08 D-9 — 컨트롤러가 넘기는 dualRequestTimeoutMs 가 create/update/delete 3곳에서 완전히 무시됐다.
     * 그러면 사용자에게는 컨테이너 기본 async timeout 으로 503 이 나가는데 백그라운드 작업은 계속 진행돼
     * 응답과 실제 결과가 어긋난다(사용자는 실패로 알고 재시도 → 동시 편집 유발).
     */
    @Test
    @DisplayName("S08 D-9: update 는 timeoutMs 를 넘기면 TimeoutException 으로 완료합니다")
    void updateAppliesTimeoutFromCaller() {
        final EqpDirectScenarioService blockingDirectService = mock(EqpDirectScenarioService.class);
        when(blockingDirectService.executeUpdate(eq("EQP-01"), any())).thenAnswer(invocation -> {
            Thread.sleep(2_000L);
            return EqpCommandResult.ok();
        });
        final EqpManagementService timeoutService = new EqpManagementService(
                mock(EqpManageQueryPort.class),
                mock(EqpOptionsQueryPort.class),
                blockingDirectService,
                asyncResultStorePort,
                Executors.newSingleThreadExecutor()
        );

        final CompletableFuture<EqpCommandResult> future =
                timeoutService.update("EQP-01", mock(EqpManagementCommand.Update.class), 100L);

        final ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.get(3, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, failure.getCause());
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
}
