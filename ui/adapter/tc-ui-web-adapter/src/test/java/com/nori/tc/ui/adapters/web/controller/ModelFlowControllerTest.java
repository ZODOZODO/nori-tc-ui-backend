package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.controller.support.UiApiExceptionHandler;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.exception.UiErrorCode;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.service.ModelFlowCommandService;
import com.nori.tc.ui.core.service.ModelFlowQueryService;
import com.nori.tc.ui.domain.model.UiMessageFlow;
import com.nori.tc.ui.domain.model.UiMessageFlowSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ModelFlowController} 의 4 엔드포인트 단위 테스트입니다.
 *
 * <p>모든 service 의존을 mock 으로 대체하고 standalone MockMvc 로 HTTP 계약만 검증합니다.
 * Production 의 service / port / DB 동작은 별도 단위 / starter 테스트가 담당합니다.</p>
 */
class ModelFlowControllerTest {

    private ModelFlowQueryService queryService;
    private ModelFlowCommandService commandService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        queryService = mock(ModelFlowQueryService.class);
        commandService = mock(ModelFlowCommandService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ModelFlowController(queryService, commandService))
                .setControllerAdvice(new UiApiExceptionHandler(UiControllerTestMessageSource.create()))
                .build();
    }

    @Test
    void createFlow_returnsFlowId() throws Exception {
        when(commandService.createFlow(eq(10L), eq("WF1"), eq("MSG1"))).thenReturn(42L);

        mockMvc.perform(post("/api/model/10/flows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowName\":\"WF1\",\"messageName\":\"MSG1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.flowId").value(42));

        verify(commandService).createFlow(eq(10L), eq("WF1"), eq("MSG1"));
    }

    @Test
    void createFlow_returnsBadRequestOnBlankWorkflowName() throws Exception {
        when(commandService.createFlow(eq(10L), any(), any()))
                .thenThrow(new UiBadRequestException("workflowName은 비어 있을 수 없습니다."));

        mockMvc.perform(post("/api/model/10/flows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowName\":\"\",\"messageName\":\"MSG1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(UiErrorCode.INVALID_REQUEST));
    }

    @Test
    void createFlow_returnsNotFoundWhenModelMissing() throws Exception {
        when(commandService.createFlow(eq(99L), any(), any()))
                .thenThrow(new UiNotFoundException("모델을 찾을 수 없습니다. modelVersionKey=99"));

        mockMvc.perform(post("/api/model/99/flows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowName\":\"WF1\",\"messageName\":\"MSG1\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(UiErrorCode.NOT_FOUND));
    }

    @Test
    void createFlow_returnsConflictOnDuplicate() throws Exception {
        when(commandService.createFlow(eq(10L), any(), any()))
                .thenThrow(new UiConflictException("DUPLICATE", "이미 동일 (workflowName, messageName) 조합이 존재합니다."));

        mockMvc.perform(post("/api/model/10/flows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowName\":\"WF1\",\"messageName\":\"MSG1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE"));
    }

    @Test
    void listFlows_returnsItems() throws Exception {
        when(queryService.findFlowSummaries(eq(10L))).thenReturn(List.of(
                new UiMessageFlowSummary(1L, "WF1", "MSG1", "ACT1"),
                new UiMessageFlowSummary(2L, "WF1", "MSG2", "ACT2")
        ));

        mockMvc.perform(get("/api/model/10/flows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].flowId").value(1))
                .andExpect(jsonPath("$.data.items[0].workflowName").value("WF1"))
                .andExpect(jsonPath("$.data.items[0].messageName").value("MSG1"))
                .andExpect(jsonPath("$.data.items[0].actionName").value("ACT1"))
                .andExpect(jsonPath("$.data.items[1].flowId").value(2));
    }

    @Test
    void getFlow_returnsFourSectionDetail() throws Exception {
        when(queryService.findFlowDetail(eq(10L), eq(1L))).thenReturn(new UiMessageFlow(
                1L,
                "WF1",
                "MSG1",
                "EVT-1",
                "TX-1",
                "{\"and\":[]}",
                "ACT1",
                "{\"fields\":{}}",
                List.of(),
                List.of()
        ));

        mockMvc.perform(get("/api/model/10/flows/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.flowId").value(1))
                .andExpect(jsonPath("$.data.workflowName").value("WF1"))
                .andExpect(jsonPath("$.data.messageName").value("MSG1"))
                .andExpect(jsonPath("$.data.eventId").value("EVT-1"))
                .andExpect(jsonPath("$.data.transactionId").value("TX-1"))
                .andExpect(jsonPath("$.data.workflowFilter").value("{\"and\":[]}"))
                .andExpect(jsonPath("$.data.actionName").value("ACT1"))
                .andExpect(jsonPath("$.data.actionDataIndex").value("{\"fields\":{}}"))
                .andExpect(jsonPath("$.data.dcopItems").isArray());
    }

    @Test
    void getFlow_returnsNotFoundWhenFlowMissing() throws Exception {
        when(queryService.findFlowDetail(eq(10L), eq(999L)))
                .thenThrow(new UiNotFoundException("Flow를 찾을 수 없습니다. modelVersionKey=10, flowId=999"));

        mockMvc.perform(get("/api/model/10/flows/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(UiErrorCode.NOT_FOUND));
    }

    @Test
    void updateFlow_returnsUpdatedDetail() throws Exception {
        when(queryService.findFlowDetail(eq(10L), eq(1L))).thenReturn(new UiMessageFlow(
                1L, "WF1u", "MSG1u", null, null, null, "ACT1u", null, List.of(), List.of()
        ));

        mockMvc.perform(put("/api/model/10/flows/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowName\":\"WF1u\",\"messageName\":\"MSG1u\",\"actionName\":\"ACT1u\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.workflowName").value("WF1u"))
                .andExpect(jsonPath("$.data.actionName").value("ACT1u"))
                // AC B15 contract — dcop-item 응답 row 에 workflowName 필드 부재 (dcopItems 가 비어도 형 유지).
                .andExpect(jsonPath("$.data.dcopItems").isArray())
                .andExpect(jsonPath("$.data.dcopItems[0].workflowName").doesNotExist());

        verify(commandService).updateFlow(eq(10L), eq(1L), any(UiMessageFlow.class));
    }

    @Test
    void updateFlow_acceptsDcopItemKeysForCollectBinding() throws Exception {
        when(queryService.findFlowDetail(eq(10L), eq(1L))).thenReturn(new UiMessageFlow(
                1L, "WF1u", "MSG1u", null, null, null, "ACT1u", null, List.of(), List.of(11L, 22L)
        ));

        mockMvc.perform(put("/api/model/10/flows/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowName\":\"WF1u\",\"messageName\":\"MSG1u\",\"actionName\":\"ACT1u\","
                                + "\"dcopItemKeys\":[11,22]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.dcopItemKeys[0]").value(11))
                .andExpect(jsonPath("$.data.dcopItemKeys[1]").value(22));

        verify(commandService).updateFlow(eq(10L), eq(1L), any(UiMessageFlow.class));
    }

    @Test
    void updateFlow_returnsBadRequestOnInvalidWorkflowFilter() throws Exception {
        // workflow_filter 가 JSON 으로 파싱은 되지만 schema (and/or 루트 누락) 가 어긋난 경우.
        mockMvc.perform(put("/api/model/10/flows/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowName\":\"WF1u\",\"messageName\":\"MSG1u\",\"actionName\":\"ACT1u\","
                                + "\"workflowFilter\":\"{\\\"foo\\\":\\\"bar\\\"}\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(UiErrorCode.INVALID_REQUEST));
    }

    @Test
    void updateFlow_returnsNotFoundWhenServiceThrows() throws Exception {
        doThrow(new UiNotFoundException("모델을 찾을 수 없습니다. modelVersionKey=99"))
                .when(commandService).updateFlow(eq(99L), anyLong(), any(UiMessageFlow.class));

        mockMvc.perform(put("/api/model/99/flows/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowName\":\"WF1u\",\"messageName\":\"MSG1u\",\"actionName\":\"ACT1u\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(UiErrorCode.NOT_FOUND));
    }

    @Test
    void deleteFlow_returnsOk() throws Exception {
        mockMvc.perform(delete("/api/model/10/flows/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(commandService).deleteFlow(eq(10L), eq(1L));
    }

    @Test
    void deleteFlow_returnsNotFoundWhenServiceThrows() throws Exception {
        doThrow(new UiNotFoundException("Flow를 찾을 수 없습니다. modelVersionKey=10, flowId=999"))
                .when(commandService).deleteFlow(eq(10L), eq(999L));

        mockMvc.perform(delete("/api/model/10/flows/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(UiErrorCode.NOT_FOUND));
    }
}
