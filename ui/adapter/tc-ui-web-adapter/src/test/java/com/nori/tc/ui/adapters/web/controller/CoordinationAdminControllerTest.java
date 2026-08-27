package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.core.coordination.UiCoordinationUnavailableException;
import com.nori.tc.ui.core.coordination.UiOperationAdminQueryService;
import com.nori.tc.ui.core.coordination.model.UiOperationAdminView;
import com.nori.tc.ui.core.coordination.model.UiOperationPhase;
import com.nori.tc.ui.core.coordination.model.UiOperationState;
import com.nori.tc.ui.core.coordination.model.UiOperationView;
import com.nori.tc.ui.core.coordination.model.UiParticipantProofView;
import com.nori.tc.ui.core.coordination.model.UiPhaseTransitionView;
import com.nori.tc.ui.core.coordination.model.UiResourceHeadView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §20.3 관리 조회 API 의 <b>실제 JSON 본문</b>을 고정합니다.
 *
 * <p>운영자가 barrier 미개방 원인을 읽으려면 resource head 와 participant proof 가 응답에 실제로
 * 실려야 합니다 — 서비스가 조립해도 DTO 변환에서 빠지면 화면에는 없습니다.</p>
 */
class CoordinationAdminControllerTest {

    private static final String OPERATION_ID = "op-1";

    @SuppressWarnings("unchecked")
    private static MockMvc mockMvcWith(final UiOperationAdminQueryService service) {
        final ObjectProvider<UiOperationAdminQueryService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return MockMvcBuilders
                .standaloneSetup(new CoordinationAdminController(provider))
                .setControllerAdvice(new com.nori.tc.ui.adapters.web.controller.support.UiApiExceptionHandler(
                        new org.springframework.context.support.StaticMessageSource()))
                .build();
    }

    @Test
    @DisplayName("resource head 와 participant proof 가 응답 본문에 실린다")
    void responseCarriesResourceHeadsAndProofs() throws Exception {
        final UiOperationAdminQueryService service = mock(UiOperationAdminQueryService.class);
        when(service.find(anyString())).thenReturn(Optional.of(new UiOperationAdminView(
                new UiOperationView(
                        OPERATION_ID, "exec-1", "CLN_001", "EQP_UPDATE",
                        UiOperationState.PENDING, UiOperationPhase.APPLYING,
                        List.of("equipment:CLN_001"), List.of("tc-business:pod-1:sess-1"),
                        "digest", 0L, 3L, false, null, null, null, null, null, 1L, 2L, null),
                List.of(new UiResourceHeadView("equipment:CLN_001", 3L, 0L, OPERATION_ID, OPERATION_ID)),
                List.of(new UiParticipantProofView(
                        "tc-business:pod-1:sess-1", 3L, "digest", null, null, 7L)),
                List.of(
                        new UiPhaseTransitionView("ACCEPTED", 100L),
                        new UiPhaseTransitionView("PREPARING", 200L),
                        new UiPhaseTransitionView("APPLYING", 300L)))));

        mockMvcWith(service)
                .perform(get("/api/coordination/operations/" + OPERATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.phase").value("APPLYING"))
                // committed 3 인데 open 0 = 장벽이 닫혀 있다. 이 대비가 관리 조회의 핵심이다.
                .andExpect(jsonPath("$.data.resources[0].committedRevision").value(3))
                .andExpect(jsonPath("$.data.resources[0].openRevision").value(0))
                .andExpect(jsonPath("$.data.resources[0].blockedByOperationId").value(OPERATION_ID))
                // prepared 만 있고 applied 가 비어 있다 = 이 participant 에서 멈춰 있다.
                .andExpect(jsonPath("$.data.participantProofs[0].preparedRevision").value(3))
                .andExpect(jsonPath("$.data.participantProofs[0].appliedRevision").doesNotExist())
                .andExpect(jsonPath("$.data.participantProofs[0].ownerEpoch").value(7))
                // 현재 phase 만으로는 되돌아온 전이나 반복 재시도를 구분할 수 없다(§20.3).
                .andExpect(jsonPath("$.data.phaseHistory.length()").value(3))
                .andExpect(jsonPath("$.data.phaseHistory[0].phase").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.phaseHistory[2].phase").value("APPLYING"))
                .andExpect(jsonPath("$.data.phaseHistory[2].atEpochMs").value(300));
    }

    @Test
    @DisplayName("없는 operation 은 404 OPERATION_NOT_FOUND 다")
    void missingOperationReturns404() throws Exception {
        final UiOperationAdminQueryService service = mock(UiOperationAdminQueryService.class);
        when(service.find(anyString())).thenReturn(Optional.empty());

        mockMvcWith(service)
                .perform(get("/api/coordination/operations/op-none"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("OPERATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("조정 원장 접근 불가는 503 COORDINATION_UNAVAILABLE 이며 404 로 환원되지 않는다")
    void ledgerUnavailableReturns503() throws Exception {
        final UiOperationAdminQueryService service = mock(UiOperationAdminQueryService.class);
        when(service.find(anyString())).thenThrow(new UiCoordinationUnavailableException("원장 접근 실패"));

        mockMvcWith(service)
                .perform(get("/api/coordination/operations/" + OPERATION_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("COORDINATION_UNAVAILABLE"));
    }

    @Test
    @DisplayName("조정 비활성 구성에서는 503 COORDINATION_DISABLED — 기동은 성공해야 한다")
    void disabledCoordinationReturns503WithDistinctCode() throws Exception {
        /*
         * 이 컨트롤러가 조회 서비스를 직접 주입받으면 비조정 구성에서 앱이 기동하지 못한다
         * (P6 통합 검증에서 같은 계열 결함으로 세 앱이 모두 기동 실패했다). provider 가 비어 있어도
         * 컨트롤러가 만들어지고 응답을 낸다는 것이 여기서 고정하는 계약이다.
         */
        mockMvcWith(null)
                .perform(get("/api/coordination/operations/" + OPERATION_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("COORDINATION_DISABLED"));
    }
}
