package com.nori.tc.ui.core.service;

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
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
