package com.nori.tc.ui.starter;

import com.nori.tc.ui.adapters.web.controller.ModelFlowController;
import com.nori.tc.ui.adapters.web.controller.support.UiApiExceptionHandler;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.exception.UiErrorCode;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.service.ModelFlowCommandService;
import com.nori.tc.ui.core.service.ModelFlowQueryService;
import com.nori.tc.ui.domain.model.UiMessageFlow;
import com.nori.tc.ui.domain.model.UiMessageFlowSummary;
import com.nori.tc.ui.domain.model.UiModelDcopItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * starter 단의 {@link ModelFlowController} 4 endpoint MockMvc 통합 테스트입니다.
 *
 * <p>SPEC {@code model-flow-restructure} line 138-140 의 Acceptance Criteria
 * (GET /api/model/{key}/flows / GET /api/model/{key}/flows/{flowId} /
 * PUT .../flows/{flowId} 3 엔드포인트 MockMvc 통과 + 200 + 응답 스키마 검증 +
 * 404 / 409 negative case 포함) 를 starter 모듈에서 검증합니다.
 * POST endpoint 는 Plan {@code abstract-jumping-comet.md} 의 4 번째 endpoint 추가
 * 결정에 따라 함께 검증합니다.</p>
 *
 * <p>구성 정책 (워크스페이스 컨벤션):</p>
 * <ul>
 *   <li>standalone MockMvc — {@code @SpringBootTest} 풀스택 도입은
 *     {@link com.nori.tc.ui.adapters.web.controller.DlqControllerSecurityTest}
 *     의 명시적 정책 ("starter 풀스택 보안 통합은 인프라 의존 과중으로 별 SPEC 분리")
 *     을 답습합니다. 워크스페이스 전체 {@code @SpringBootTest} / {@code @WebMvcTest}
 *     형제 0 건.</li>
 *   <li>service 의존은 {@link org.mockito.Mockito#mock} — fixture H2 / embedded DB
 *     seed 형제 패턴 부재.</li>
 *   <li>{@link UiApiExceptionHandler} 를 {@code setControllerAdvice} 로 명시 활성화 —
 *     UiBadRequest/UiNotFound/UiConflict 의 HTTP 400/404/409 매핑을 starter 와 동일
 *     advice 그래프로 검증합니다.</li>
 *   <li>401 negative case 는 standalone setup 에 Spring Security filter 가 참여하지
 *     않으므로 본 SPEC 범위에서 제외 — 별 SPEC (starter 풀스택 보안 통합) 으로 분리.
 *     본 테스트의 400 / 404 / 409 는 모두 advice 매핑 적용 후 응답 스키마까지 검증.</li>
 * </ul>
 */
class ModelFlowStarterMockMvcTest {

    private ModelFlowQueryService queryService;
    private ModelFlowCommandService commandService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        queryService = mock(ModelFlowQueryService.class);
        commandService = mock(ModelFlowCommandService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ModelFlowController(queryService, commandService))
                .setControllerAdvice(new UiApiExceptionHandler())
                .build();
    }

    // ---- POST /api/model/{modelVersionKey}/flows ---------------------------------------

    @Test
    @DisplayName("POST /flows — 정상 본문이면 200 + flowId 응답")
    void createFlow_returnsFlowIdOn200() throws Exception {
        when(commandService.createFlow(eq(10L), eq("WF1"), eq("MSG1"))).thenReturn(42L);

        mockMvc.perform(post("/api/model/10/flows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowName\":\"WF1\",\"messageName\":\"MSG1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.flowId").value(42))
                .andExpect(jsonPath("$.errorCode").doesNotExist());

        verify(commandService).createFlow(eq(10L), eq("WF1"), eq("MSG1"));
    }

    @Test
    @DisplayName("POST /flows — workflowName blank 이면 400 + INVALID_REQUEST")
    void createFlow_returns400OnBlankWorkflowName() throws Exception {
        when(commandService.createFlow(eq(10L), any(), any()))
                .thenThrow(new UiBadRequestException("workflowName은 비어 있을 수 없습니다."));

        mockMvc.perform(post("/api/model/10/flows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowName\":\"\",\"messageName\":\"MSG1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(UiErrorCode.INVALID_REQUEST))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("POST /flows — modelVersionKey 미존재이면 404 + NOT_FOUND")
    void createFlow_returns404WhenModelMissing() throws Exception {
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
    @DisplayName("POST /flows — (workflowName, messageName) unique 충돌이면 409 + DUPLICATE")
    void createFlow_returns409OnUniqueViolation() throws Exception {
        when(commandService.createFlow(eq(10L), any(), any()))
                .thenThrow(new UiConflictException(
                        "DUPLICATE",
                        "이미 동일 (workflowName, messageName) 조합이 존재합니다."
                ));

        mockMvc.perform(post("/api/model/10/flows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowName\":\"WF1\",\"messageName\":\"MSG1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE"));
    }

    // ---- GET /api/model/{modelVersionKey}/flows ----------------------------------------

    @Test
    @DisplayName("GET /flows — workflow 0 건 모델은 200 + 빈 items 배열")
    void listFlows_returns200WithEmptyArrayWhenNoWorkflow() throws Exception {
        when(queryService.findFlowSummaries(eq(10L))).thenReturn(List.of());

        mockMvc.perform(get("/api/model/10/flows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    @DisplayName("GET /flows — 3 건 응답이면 items[*] 의 4 필드 (flowId/workflowName/messageName/actionName) 모두 노출")
    void listFlows_returns200WithThreeSummaries() throws Exception {
        when(queryService.findFlowSummaries(eq(10L))).thenReturn(List.of(
                new UiMessageFlowSummary(1L, "handleLotStart", "S6F11", "PUBLISH_MES_COMMAND"),
                new UiMessageFlowSummary(2L, "handleLotStart", "S6F13", "PUBLISH_MES_COMMAND"),
                new UiMessageFlowSummary(3L, "reportToMes", "S6F11", "PUBLISH_MES_COMMAND")
        ));

        mockMvc.perform(get("/api/model/10/flows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[0].flowId").value(1))
                .andExpect(jsonPath("$.data.items[0].workflowName").value("handleLotStart"))
                .andExpect(jsonPath("$.data.items[0].messageName").value("S6F11"))
                .andExpect(jsonPath("$.data.items[0].actionName").value("PUBLISH_MES_COMMAND"))
                .andExpect(jsonPath("$.data.items[1].flowId").value(2))
                .andExpect(jsonPath("$.data.items[1].messageName").value("S6F13"))
                .andExpect(jsonPath("$.data.items[2].flowId").value(3))
                .andExpect(jsonPath("$.data.items[2].workflowName").value("reportToMes"));
    }

    // ---- GET /api/model/{modelVersionKey}/flows/{flowId} -------------------------------

    @Test
    @DisplayName("GET /flows/{flowId} — SECS Flow 는 Trigger 영역 (eventId/transactionId/workflowFilter) 채워짐")
    void getFlow_returns200WithSecsTriggerFields() throws Exception {
        when(queryService.findFlowDetail(eq(10L), eq(1L))).thenReturn(new UiMessageFlow(
                1L,
                "handleLotStart",
                "S6F11",
                "1001",
                "T01",
                "{\"and\":[{\"from\":\"data\",\"path\":\"status\",\"comparison\":\"equals\",\"expected\":\"ok\"}]}",
                "PUBLISH_MES_COMMAND",
                "{\"mdfTemplateName\":\"TOOL_CONDITION_REPLY_MES\",\"fields\":{}}",
                List.of(),
                List.of()
        ));

        mockMvc.perform(get("/api/model/10/flows/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.flowId").value(1))
                .andExpect(jsonPath("$.data.workflowName").value("handleLotStart"))
                .andExpect(jsonPath("$.data.messageName").value("S6F11"))
                .andExpect(jsonPath("$.data.eventId").value("1001"))
                .andExpect(jsonPath("$.data.transactionId").value("T01"))
                .andExpect(jsonPath("$.data.workflowFilter").exists())
                .andExpect(jsonPath("$.data.actionName").value("PUBLISH_MES_COMMAND"))
                .andExpect(jsonPath("$.data.actionDataIndex").exists())
                .andExpect(jsonPath("$.data.dcopItems").isArray());
    }

    @Test
    @DisplayName("GET /flows/{flowId} — Socket Flow 는 Trigger 영역 (eventId/transactionId/workflowFilter) 모두 null")
    void getFlow_returns200WithSocketTriggerNull() throws Exception {
        when(queryService.findFlowDetail(eq(20L), eq(7L))).thenReturn(new UiMessageFlow(
                7L,
                "handleSocketLogin",
                "LOGIN_REQ",
                null,
                null,
                null,
                "PUBLISH_MES_COMMAND",
                null,
                List.<UiModelDcopItem>of(),
                List.of()
        ));

        mockMvc.perform(get("/api/model/20/flows/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.flowId").value(7))
                .andExpect(jsonPath("$.data.workflowName").value("handleSocketLogin"))
                .andExpect(jsonPath("$.data.messageName").value("LOGIN_REQ"))
                .andExpect(jsonPath("$.data.eventId").doesNotExist())
                .andExpect(jsonPath("$.data.transactionId").doesNotExist())
                .andExpect(jsonPath("$.data.workflowFilter").doesNotExist())
                .andExpect(jsonPath("$.data.actionName").value("PUBLISH_MES_COMMAND"))
                .andExpect(jsonPath("$.data.dcopItems").isArray())
                .andExpect(jsonPath("$.data.dcopItems.length()").value(0));
    }

    @Test
    @DisplayName("GET /flows/{flowId} — 없는 flowId 이면 404 + NOT_FOUND")
    void getFlow_returns404WhenFlowMissing() throws Exception {
        when(queryService.findFlowDetail(eq(10L), eq(999L)))
                .thenThrow(new UiNotFoundException("Flow를 찾을 수 없습니다. modelVersionKey=10, flowId=999"));

        mockMvc.perform(get("/api/model/10/flows/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(UiErrorCode.NOT_FOUND));
    }

    // ---- PUT /api/model/{modelVersionKey}/flows/{flowId} -------------------------------

    @Test
    @DisplayName("PUT /flows/{flowId} — 정상 갱신이면 200 + 재조회된 detail 응답")
    void updateFlow_returns200WithUpdatedDetail() throws Exception {
        when(queryService.findFlowDetail(eq(10L), eq(1L))).thenReturn(new UiMessageFlow(
                1L,
                "handleLotStart",
                "S6F11",
                "1001",
                null,
                null,
                "PUBLISH_MES_COMMAND_v2",
                null,
                List.of(),
                List.of()
        ));

        mockMvc.perform(put("/api/model/10/flows/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowName\":\"handleLotStart\",\"messageName\":\"S6F11\","
                                + "\"eventId\":\"1001\",\"actionName\":\"PUBLISH_MES_COMMAND_v2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.flowId").value(1))
                .andExpect(jsonPath("$.data.workflowName").value("handleLotStart"))
                .andExpect(jsonPath("$.data.messageName").value("S6F11"))
                .andExpect(jsonPath("$.data.eventId").value("1001"))
                .andExpect(jsonPath("$.data.actionName").value("PUBLISH_MES_COMMAND_v2"));

        verify(commandService).updateFlow(eq(10L), eq(1L), any(UiMessageFlow.class));
    }

    @Test
    @DisplayName("PUT /flows/{flowId} — Collect 섹션 dcopItemKeys round-trip + N:M 시나리오 응답 schema")
    void updateFlow_returns200WithCollectRoundTrip() throws Exception {
        when(queryService.findFlowDetail(eq(10L), eq(1L))).thenReturn(new UiMessageFlow(
                1L,
                "handleLotStart",
                "S6F11",
                "1001",
                null,
                null,
                "PUBLISH_MES_COMMAND",
                null,
                List.<UiModelDcopItem>of(),
                List.of(101L, 102L)
        ));

        mockMvc.perform(put("/api/model/10/flows/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowName\":\"handleLotStart\",\"messageName\":\"S6F11\","
                                + "\"eventId\":\"1001\",\"actionName\":\"PUBLISH_MES_COMMAND\","
                                + "\"dcopItemKeys\":[101,102]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // 응답 schema 에 dcopItemKeys 노출 — FE 가 round-trip 시 동일 배열 송신 가능.
                .andExpect(jsonPath("$.data.dcopItemKeys").isArray())
                .andExpect(jsonPath("$.data.dcopItemKeys.length()").value(2))
                .andExpect(jsonPath("$.data.dcopItemKeys[0]").value(101))
                .andExpect(jsonPath("$.data.dcopItemKeys[1]").value(102));

        verify(commandService).updateFlow(eq(10L), eq(1L), any(UiMessageFlow.class));
    }

    @Test
    @DisplayName("PUT /flows/{flowId} — workflow_filter schema 위반이면 400 + INVALID_REQUEST")
    void updateFlow_returns400OnInvalidWorkflowFilter() throws Exception {
        // workflow_filter 가 JSON 으로 파싱은 되지만 schema (and/or 루트 누락) 가 어긋난 경우.
        // controller 가 service 호출 전 ModelDetailWorkflowJsonSupport 로 schema 검증 후
        // UiBadRequestException 으로 변환 → 400.
        mockMvc.perform(put("/api/model/10/flows/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowName\":\"WF1\",\"messageName\":\"MSG1\","
                                + "\"actionName\":\"ACT1\","
                                + "\"workflowFilter\":\"{\\\"foo\\\":\\\"bar\\\"}\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(UiErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("PUT /flows/{flowId} — action_data_index schema 위반이면 400 + INVALID_REQUEST")
    void updateFlow_returns400OnInvalidActionDataIndex() throws Exception {
        // action_data_index 가 JSON 으로 파싱은 되지만 schema (fields 객체 누락 등) 가 어긋난 경우.
        // workflow_filter 는 통과하는 schema 로 두어 action_data_index 검증 경로만 분리 확인.
        mockMvc.perform(put("/api/model/10/flows/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowName\":\"WF1\",\"messageName\":\"MSG1\","
                                + "\"actionName\":\"ACT1\","
                                + "\"workflowFilter\":\"{\\\"and\\\":[]}\","
                                + "\"actionDataIndex\":\"[1,2,3]\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(UiErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("PUT /flows/{flowId} — 없는 flowId 이면 404 + NOT_FOUND")
    void updateFlow_returns404WhenServiceThrows() throws Exception {
        doThrow(new UiNotFoundException("Flow를 찾을 수 없습니다. modelVersionKey=10, flowId=999"))
                .when(commandService).updateFlow(eq(10L), anyLong(), any(UiMessageFlow.class));

        mockMvc.perform(put("/api/model/10/flows/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowName\":\"WF1\",\"messageName\":\"MSG1\","
                                + "\"actionName\":\"ACT1\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(UiErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("PUT /flows/{flowId} — (workflowName, messageName) unique 충돌이면 409 + DUPLICATE")
    void updateFlow_returns409OnUniqueViolation() throws Exception {
        doThrow(new UiConflictException(
                "DUPLICATE",
                "이미 동일 (workflowName, messageName) 조합이 존재합니다."
        )).when(commandService).updateFlow(eq(10L), anyLong(), any(UiMessageFlow.class));

        mockMvc.perform(put("/api/model/10/flows/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowName\":\"WF1\",\"messageName\":\"MSG1\","
                                + "\"actionName\":\"ACT1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE"));
    }
}
