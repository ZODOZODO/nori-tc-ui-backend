package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.core.coordination.UiCoordinationProperties;
import com.nori.tc.ui.core.coordination.UiOperationQueryService;
import com.nori.tc.ui.core.coordination.model.UiOperationPhase;
import com.nori.tc.ui.core.coordination.model.UiOperationState;
import com.nori.tc.ui.core.coordination.model.UiOperationView;
import com.nori.tc.ui.core.coordination.port.UiOperationLedgerPort;
import com.nori.tc.ui.core.model.AsyncResultEntry;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.port.redis.AsyncResultStorePort;
import com.nori.tc.ui.domain.task.UiTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §13.3 — {@code GET /api/async/{id}} 의 operation 조회 계약을 고정합니다.
 *
 * <p>특히 <b>TIMEOUT terminal 이 없다</b>는 것을 고정합니다. 종전에는 처리 deadline 이 지나면
 * {@code 408 TIMEOUT} 을 돌려줬고, 그 값을 받은 Front 는 실패로 확정했습니다 — 그 뒤 실제로 성공한
 * 적용이 도착해도 결과를 되돌릴 수 없었습니다.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsyncResultControllerTest {

    private static final long NOW = 1_900_000_000_000L;

    @Mock
    private AsyncResultStorePort asyncResultStorePort;

    @Mock
    private UiOperationLedgerPort ledgerPort;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(ledgerPort.readOperation(anyString())).thenReturn(Optional.empty());
        final UiOperationQueryService queryService = new UiOperationQueryService(
                ledgerPort, asyncResultStorePort, new UiCoordinationProperties());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AsyncResultController(providerOf(queryService)))
                .build();
    }

    @Test
    @DisplayName("조정 원장이 비활성인 구성에서는 503 이고 404 로 환원하지 않는다")
    void coordinationDisabledReturnsServiceUnavailable() throws Exception {
        /*
         * 이 컨트롤러가 조회 서비스를 직접 주입받던 동안에는 조정 비활성 구성에서 앱이 기동조차
         * 하지 못했다(UnsatisfiedDependencyException — 2026-08-26 프로덕션 배포 실패).
         * 404 로 환원하지 않는 이유는 Front 가 "작업이 사라졌다" 로 읽고 재요청해 중복 mutation 을
         * 만들기 때문이다.
         */
        final MockMvc disabled = MockMvcBuilders
                .standaloneSetup(new AsyncResultController(providerOf(null)))
                .build();

        disabled.perform(get("/api/async/op-1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("COORDINATION_DISABLED"));
    }

    /** 조정 on/off 두 구성을 모사하는 공급자 — null 이면 빈 공급자다. */
    private static ObjectProvider<UiOperationQueryService> providerOf(final UiOperationQueryService service) {
        return new ObjectProvider<>() {

            @Override
            public UiOperationQueryService getObject() {
                if (service == null) {
                    throw new NoSuchBeanDefinitionException(UiOperationQueryService.class);
                }
                return service;
            }

            @Override
            public UiOperationQueryService getIfAvailable() {
                return service;
            }
        };
    }

    @Test
    @DisplayName("비terminal operation 은 202 + PENDING 이다")
    void pendingOperationReturnsAccepted() throws Exception {
        when(ledgerPort.readOperation("op-1")).thenReturn(Optional.of(operation(
                "op-1", UiOperationState.PENDING, UiOperationPhase.PREPARING, NOW)));

        mockMvc.perform(get("/api/async/op-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.operationId").value("op-1"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.phase").value("PREPARING"));
    }

    @Test
    @DisplayName("terminal FAIL 은 200 + FAIL 이다")
    void terminalFailureReturnsOk() throws Exception {
        when(ledgerPort.readOperation("op-2")).thenReturn(Optional.of(new UiOperationView(
                "op-2", null, "PHOTO_001", "EQP_UPDATE", UiOperationState.FAIL, UiOperationPhase.ABORTED,
                List.of("equipment:PHOTO_001"), List.of(), "a".repeat(64), 0L, 7L, false, null,
                "START_FAILED", "설비 시작에 실패했습니다.", null, NOW, NOW, NOW)));

        mockMvc.perform(get("/api/async/op-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eqpId").value("PHOTO_001"))
                .andExpect(jsonPath("$.data.status").value("FAIL"))
                .andExpect(jsonPath("$.data.errorCode").value("START_FAILED"))
                .andExpect(jsonPath("$.data.desiredRevision").value(7));
    }

    @Test
    @DisplayName("관찰 deadline 을 넘겨도 terminal 이 되지 않고 RECONCILING 으로만 표시된다")
    void staleOperationIsReconcilingNotTimeout() throws Exception {
        // 마지막 전이가 관찰 deadline 보다 오래 전이다.
        when(ledgerPort.readOperation("op-3")).thenReturn(Optional.of(operation(
                "op-3", UiOperationState.PENDING, UiOperationPhase.APPLYING, 1L)));

        mockMvc.perform(get("/api/async/op-3"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.phase").value("RECONCILING"));
    }

    @Test
    @DisplayName("레거시 TIMEOUT 엔트리도 terminal 이 아니라 PENDING+RECONCILING 으로 환원된다")
    void legacyTimeoutEntryIsNotTerminal() throws Exception {
        when(asyncResultStorePort.getWithStatus("trace-timeout"))
                .thenReturn(Optional.of(AsyncResultEntry.timeout("trace-timeout")));

        mockMvc.perform(get("/api/async/trace-timeout"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.phase").value("RECONCILING"));
    }

    @Test
    @DisplayName("레거시 완료 엔트리는 200 + 원 결과로 환원된다")
    void legacyCompletedEntryReturnsOriginalResult() throws Exception {
        final UiCommandReply reply = new UiCommandReply(
                "trace-completed", "TC-GATEWAY-APP", "EQP_START_REP", "PHOTO_001", "SOCKET",
                UiTaskStatus.FAIL, "START_FAILED", "설비 시작에 실패했습니다.");
        when(asyncResultStorePort.getWithStatus("trace-completed"))
                .thenReturn(Optional.of(AsyncResultEntry.completed("trace-completed", reply)));

        mockMvc.perform(get("/api/async/trace-completed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operationId").value("trace-completed"))
                .andExpect(jsonPath("$.data.eqpId").value("PHOTO_001"))
                .andExpect(jsonPath("$.data.status").value("FAIL"))
                .andExpect(jsonPath("$.data.errorCode").value("START_FAILED"));
    }

    @Test
    @DisplayName("존재하지 않는 ID 는 404 다")
    void missingIdReturnsNotFound() throws Exception {
        when(asyncResultStorePort.getWithStatus("trace-missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/async/trace-missing"))
                .andExpect(status().isNotFound());
    }

    private static UiOperationView operation(
            final String operationId,
            final UiOperationState state,
            final UiOperationPhase phase,
            final long updatedAtEpochMs
    ) {
        return new UiOperationView(
                operationId, null, "PHOTO_001", "EQP_UPDATE", state, phase,
                List.of("equipment:PHOTO_001"), List.of(), "a".repeat(64), 0L, 7L, false, null,
                null, null, null, updatedAtEpochMs, updatedAtEpochMs, null);
    }
}
