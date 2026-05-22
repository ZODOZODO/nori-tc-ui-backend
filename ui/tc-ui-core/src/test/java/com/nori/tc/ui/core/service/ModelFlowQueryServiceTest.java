package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.port.db.ModelFlowQueryPort;
import com.nori.tc.ui.domain.model.UiMessageFlow;
import com.nori.tc.ui.domain.model.UiMessageFlowSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ModelFlowQueryService} 단위 테스트.
 *
 * <p>SPEC line 131-135 의 Acceptance Criteria 매핑:
 * <ul>
 *   <li>(workflow_name, message_name) 그룹화 정확성 — port 의 flat 반환을 service 가 그대로 전달.</li>
 *   <li>Socket vs SECS 분기 — port 가 채운 record 의 Trigger 영역 null 을 service 가 변형 없이 전달.</li>
 * </ul></p>
 */
class ModelFlowQueryServiceTest {

    private static final long MODEL_VERSION_KEY = 2401L;

    private ModelFlowQueryPort modelFlowQueryPort;
    private ModelFlowQueryService modelFlowQueryService;

    @BeforeEach
    void setUp() {
        modelFlowQueryPort = mock(ModelFlowQueryPort.class);
        modelFlowQueryService = new ModelFlowQueryService(modelFlowQueryPort);
    }

    @Test
    void findFlowSummariesDelegatesToPortPreservingOrder() {
        final List<UiMessageFlowSummary> portResult = List.of(
                new UiMessageFlowSummary(11L, "handleLotStart", "S6F11", "DispatchToBusiness"),
                new UiMessageFlowSummary(12L, "handleLotStart", "S6F13", "DispatchToBusiness"),
                new UiMessageFlowSummary(13L, "reportToMes", "S6F11", "ForwardToMes")
        );
        when(modelFlowQueryPort.findFlowSummaries(MODEL_VERSION_KEY)).thenReturn(portResult);

        final List<UiMessageFlowSummary> result = modelFlowQueryService.findFlowSummaries(MODEL_VERSION_KEY);

        assertEquals(portResult, result);
    }

    @Test
    void findFlowDetailReturnsPortAggregateWhenPresent() {
        final UiMessageFlow aggregate = new UiMessageFlow(
                11L,
                "handleLotStart",
                "S6F11",
                "EVT_LOT_START",
                "TRX-001",
                "{\"type\":\"AND\",\"children\":[]}",
                "DispatchToBusiness",
                "{\"mdfTemplateName\":\"M1\",\"fields\":[]}",
                List.of(),
                List.of()
        );
        when(modelFlowQueryPort.findFlowByMessageFlowKey(MODEL_VERSION_KEY, 11L))
                .thenReturn(Optional.of(aggregate));

        final UiMessageFlow result = modelFlowQueryService.findFlowDetail(MODEL_VERSION_KEY, 11L);

        assertEquals(aggregate, result);
    }

    @Test
    void findFlowDetailThrowsUiNotFoundWhenPortEmpty() {
        when(modelFlowQueryPort.findFlowByMessageFlowKey(MODEL_VERSION_KEY, 999L))
                .thenReturn(Optional.empty());

        final UiNotFoundException exception = assertThrows(
                UiNotFoundException.class,
                () -> modelFlowQueryService.findFlowDetail(MODEL_VERSION_KEY, 999L)
        );
        // 메시지에 두 식별자 모두 포함되어 디버그 가능해야 한다.
        final String message = exception.getMessage();
        assertEquals(true, message.contains("999"));
        assertEquals(true, message.contains(String.valueOf(MODEL_VERSION_KEY)));
    }

    @Test
    void findFlowDetailPreservesSocketProtocolNullTriggerFields() {
        // Socket 프로토콜 케이스: port adapter (B5) 가 Trigger 영역 3 필드를 null 로 채워 반환한다.
        // service 는 분기 로직 없이 record 를 그대로 전달해야 한다.
        final UiMessageFlow socketAggregate = new UiMessageFlow(
                21L,
                "socketRoute",
                "SOCK_MSG_A",
                null,
                null,
                null,
                "DispatchSocket",
                null,
                List.of(),
                List.of()
        );
        when(modelFlowQueryPort.findFlowByMessageFlowKey(MODEL_VERSION_KEY, 21L))
                .thenReturn(Optional.of(socketAggregate));

        final UiMessageFlow result = modelFlowQueryService.findFlowDetail(MODEL_VERSION_KEY, 21L);

        assertNull(result.eventId());
        assertNull(result.transactionId());
        assertNull(result.workflowFilter());
        assertEquals("socketRoute", result.workflowName());
        assertEquals("SOCK_MSG_A", result.messageName());
    }
}
