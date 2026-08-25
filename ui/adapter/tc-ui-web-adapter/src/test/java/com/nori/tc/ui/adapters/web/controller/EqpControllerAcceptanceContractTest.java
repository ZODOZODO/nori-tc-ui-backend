package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.config.UiDualRequestProperties;
import com.nori.tc.ui.core.coordination.UiCoordinationUnavailableException;
import com.nori.tc.ui.core.coordination.UiOperationConflictException;
import com.nori.tc.ui.core.coordination.model.UiOperationAcceptance;
import com.nori.tc.ui.core.coordination.model.UiOperationPhase;
import com.nori.tc.ui.core.coordination.model.UiOperationState;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.core.service.EqpManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §13.2 / §14.1 — Front 가 소비할 접수 응답의 <b>실제 JSON 본문</b>을 고정합니다.
 *
 * <p>Front 계약을 문서로만 맞추면 필드명이나 상태 문자열이 어긋나도 백엔드 테스트는 통과합니다.
 * 여기서는 렌더된 본문을 직접 확인해 P4 가 그대로 타입을 만들 수 있게 합니다.</p>
 */
@ExtendWith(MockitoExtension.class)
class EqpControllerAcceptanceContractTest {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    @Mock
    private EqpQueryPort eqpQueryPort;

    @Mock
    private EqpManagementService eqpManagementService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        final UiDualRequestProperties properties = new UiDualRequestProperties();
        properties.setDualRequestTimeoutMs(15_000L);
        properties.validate();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new EqpController(eqpQueryPort, eqpManagementService, properties))
                .setControllerAdvice(new com.nori.tc.ui.adapters.web.controller.support.UiApiExceptionHandler(
                        new org.springframework.context.support.StaticMessageSource()))
                .build();
    }

    @Test
    @DisplayName("정상 접수는 202 + operationId/status=PENDING/phase=ACCEPTED 다")
    void acceptedDeleteReturns202WithOperationId() throws Exception {
        when(eqpManagementService.acceptDelete(eq("PHOTO_001"), eq("KEY-1")))
                .thenReturn(UiOperationAcceptance.accepted("op-abc"));

        mockMvc.perform(delete("/api/eqp/PHOTO_001").header(IDEMPOTENCY_KEY, "KEY-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.operationId").value("op-abc"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.phase").value("ACCEPTED"));
    }

    @Test
    @DisplayName("terminal 재요청은 202 에 원 결과 status/errorCode 를 함께 싣는다")
    void replayCarriesOriginalTerminalResult() throws Exception {
        when(eqpManagementService.acceptDelete(eq("PHOTO_001"), eq("KEY-1")))
                .thenReturn(new UiOperationAcceptance(
                        "op-abc", UiOperationState.FAIL, UiOperationPhase.ABORTED, false,
                        "PLC_SPEC_INVALID", "PLC spec 변환 실패"));

        mockMvc.perform(delete("/api/eqp/PHOTO_001").header(IDEMPOTENCY_KEY, "KEY-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("FAIL"))
                .andExpect(jsonPath("$.data.phase").value("ABORTED"))
                .andExpect(jsonPath("$.data.errorCode").value("PLC_SPEC_INVALID"));
    }

    @Test
    @DisplayName("같은 key 다른 내용은 409 IDEMPOTENCY_CONFLICT 다")
    void idempotencyConflictReturns409() throws Exception {
        when(eqpManagementService.acceptDelete(eq("PHOTO_001"), eq("KEY-1")))
                .thenThrow(new UiOperationConflictException(
                        UiOperationConflictException.IDEMPOTENCY_CONFLICT, "다른 요청 내용", "op-abc"));

        mockMvc.perform(delete("/api/eqp/PHOTO_001").header(IDEMPOTENCY_KEY, "KEY-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    @DisplayName("같은 설비의 다른 작업 진행 중이면 409 RESOURCE_BUSY 다")
    void resourceBusyReturns409() throws Exception {
        when(eqpManagementService.acceptDelete(eq("PHOTO_001"), any()))
                .thenThrow(new UiOperationConflictException(
                        UiOperationConflictException.RESOURCE_BUSY, "진행 중", "op-other"));

        mockMvc.perform(delete("/api/eqp/PHOTO_001").header(IDEMPOTENCY_KEY, "KEY-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_BUSY"));
    }

    @Test
    @DisplayName("조정 원장 접근 불가는 503 COORDINATION_UNAVAILABLE 이며 404 로 환원되지 않는다")
    void coordinationUnavailableReturns503() throws Exception {
        when(eqpManagementService.acceptDelete(eq("PHOTO_001"), any()))
                .thenThrow(new UiCoordinationUnavailableException("원장 접근 실패"));

        mockMvc.perform(delete("/api/eqp/PHOTO_001").header(IDEMPOTENCY_KEY, "KEY-3"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("COORDINATION_UNAVAILABLE"));
    }

    @Test
    @DisplayName("Idempotency-Key 헤더가 없어도 접수는 거부하지 않는다(구 Front 호환)")
    void missingIdempotencyKeyStillAccepts() throws Exception {
        when(eqpManagementService.acceptDelete(eq("PHOTO_001"), any()))
                .thenReturn(UiOperationAcceptance.accepted("op-generated"));

        mockMvc.perform(delete("/api/eqp/PHOTO_001"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.operationId").value("op-generated"));
    }
}
