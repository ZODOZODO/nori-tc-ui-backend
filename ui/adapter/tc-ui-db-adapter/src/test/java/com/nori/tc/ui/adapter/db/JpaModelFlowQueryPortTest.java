package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.model.store.TcModelDcopItemStore;
import com.nori.tc.db.core.model.store.TcModelWorkflowStore;
import com.nori.tc.db.domain.common.model.DcopCollectionRule;
import com.nori.tc.db.domain.model.TcModelDcopItem;
import com.nori.tc.db.domain.model.TcModelWorkflow;
import com.nori.tc.ui.domain.model.UiMessageFlow;
import com.nori.tc.ui.domain.model.UiMessageFlowSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link JpaModelFlowQueryPort} 의 read-side 계약을 검증합니다.
 */
class JpaModelFlowQueryPortTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-20T10:15:30+09:00");

    @Test
    @DisplayName("findFlowSummaries는 workflow row를 workflow_key ASC로 정렬해 요약을 반환한다")
    void findFlowSummariesReturnsSortedByWorkflowKeyAsc() {
        final Fixture fixture = new Fixture();
        when(fixture.modelWorkflowStore.findAllByModelVersionKey(eq(101L), any(PageRequest.class)))
                .thenReturn(List.of(
                        workflow(33L, 101L, "WF_B", "S6F11", "actB", null, null, null),
                        workflow(11L, 101L, "WF_A", "S6F13", "actA", null, null, null),
                        workflow(22L, 101L, "WF_C", "S6F15", "actC", null, null, null)
                ))
                .thenReturn(List.of());

        final List<UiMessageFlowSummary> summaries = fixture.port.findFlowSummaries(101L);

        assertEquals(3, summaries.size());
        assertEquals(11L, summaries.get(0).flowId());
        assertEquals(22L, summaries.get(1).flowId());
        assertEquals(33L, summaries.get(2).flowId());
        assertEquals("WF_A", summaries.get(0).workflowName());
        assertEquals("S6F13", summaries.get(0).messageName());
        assertEquals("actA", summaries.get(0).actionName());
    }

    @Test
    @DisplayName("findFlowSummaries는 workflow가 0건이면 빈 list를 반환한다")
    void findFlowSummariesReturnsEmptyWhenNoWorkflows() {
        final Fixture fixture = new Fixture();
        when(fixture.modelWorkflowStore.findAllByModelVersionKey(anyLong(), any(PageRequest.class)))
                .thenReturn(List.of());

        final List<UiMessageFlowSummary> summaries = fixture.port.findFlowSummaries(101L);

        assertTrue(summaries.isEmpty());
    }

    @Test
    @DisplayName("findFlowByWorkflowKey는 SECS workflow에 workflow_name 매칭 DCOP 2건을 묶어 반환한다")
    void findFlowByWorkflowKeyReturnsDetailWithMatchingDcops() {
        final Fixture fixture = new Fixture();
        final TcModelWorkflow workflow = workflow(
                77L, 101L, "WF_A", "S6F11", "actA", "EVT_1", "TX_1", "{\"op\":\"EQUAL\"}"
        );
        when(fixture.modelWorkflowStore.findByWorkflowKey(77L)).thenReturn(Optional.of(workflow));
        when(fixture.modelDcopItemStore.findAllByModelVersionKey(eq(101L), any(PageRequest.class)))
                .thenReturn(List.of(
                        dcopItem(101L, 101L, "DCOP_X", "WF_A"),
                        dcopItem(102L, 101L, "DCOP_Y", "WF_OTHER"),
                        dcopItem(103L, 101L, "DCOP_Z", "WF_A")
                ))
                .thenReturn(List.of());

        final Optional<UiMessageFlow> detail = fixture.port.findFlowByWorkflowKey(101L, 77L);

        assertTrue(detail.isPresent());
        assertEquals(77L, detail.get().flowId());
        assertEquals("WF_A", detail.get().workflowName());
        assertEquals("EVT_1", detail.get().eventId());
        assertEquals("TX_1", detail.get().transactionId());
        assertEquals(2, detail.get().dcopItems().size());
        assertEquals("DCOP_X", detail.get().dcopItems().get(0).dcopItemName());
        assertEquals("DCOP_Z", detail.get().dcopItems().get(1).dcopItemName());
    }

    @Test
    @DisplayName("findFlowByWorkflowKey는 workflow row가 없으면 Optional.empty를 반환한다")
    void findFlowByWorkflowKeyReturnsEmptyWhenWorkflowMissing() {
        final Fixture fixture = new Fixture();
        when(fixture.modelWorkflowStore.findByWorkflowKey(9999L)).thenReturn(Optional.empty());

        final Optional<UiMessageFlow> detail = fixture.port.findFlowByWorkflowKey(101L, 9999L);

        assertTrue(detail.isEmpty());
    }

    @Test
    @DisplayName("findFlowByWorkflowKey는 workflow.modelVersionKey가 다르면 Optional.empty를 반환한다")
    void findFlowByWorkflowKeyReturnsEmptyWhenModelVersionMismatch() {
        final Fixture fixture = new Fixture();
        // workflow 77 은 modelVersionKey=999 에 속함. 호출자는 modelVersionKey=101 로 조회 시도.
        when(fixture.modelWorkflowStore.findByWorkflowKey(77L))
                .thenReturn(Optional.of(workflow(77L, 999L, "WF_OTHER", "S6F11", "actX", null, null, null)));

        final Optional<UiMessageFlow> detail = fixture.port.findFlowByWorkflowKey(101L, 77L);

        assertTrue(detail.isEmpty());
    }

    @Test
    @DisplayName("findFlowByWorkflowKey는 Socket workflow (Trigger 3필드 null) 를 null 보존해 반환한다")
    void findFlowByWorkflowKeyPreservesSocketTriggerNulls() {
        final Fixture fixture = new Fixture();
        final TcModelWorkflow socket = workflow(77L, 101L, "WF_S", "SOCK_MSG", "actS", null, null, null);
        when(fixture.modelWorkflowStore.findByWorkflowKey(77L)).thenReturn(Optional.of(socket));
        when(fixture.modelDcopItemStore.findAllByModelVersionKey(eq(101L), any(PageRequest.class)))
                .thenReturn(List.of());

        final Optional<UiMessageFlow> detail = fixture.port.findFlowByWorkflowKey(101L, 77L);

        assertTrue(detail.isPresent());
        assertNull(detail.get().eventId());
        assertNull(detail.get().transactionId());
        assertNull(detail.get().workflowFilter());
        assertEquals(0, detail.get().dcopItems().size());
    }

    private static TcModelWorkflow workflow(
            final long workflowKey,
            final long modelVersionKey,
            final String workflowName,
            final String messageName,
            final String actionName,
            final String eventId,
            final String transactionId,
            final String workflowFilter
    ) {
        return new TcModelWorkflow(
                workflowKey,
                modelVersionKey,
                workflowName,
                messageName,
                eventId,
                transactionId,
                workflowFilter,
                actionName,
                null,
                NOW
        );
    }

    private static TcModelDcopItem dcopItem(
            final long dcopItemKey,
            final long modelVersionKey,
            final String dcopItemName,
            final String workflowName
    ) {
        return new TcModelDcopItem(
                dcopItemKey,
                modelVersionKey,
                dcopItemName,
                workflowName,
                null,
                null,
                DcopCollectionRule.LAST,
                null,
                1,
                NOW
        );
    }

    private static final class Fixture {

        private final TcModelWorkflowStore modelWorkflowStore = mock(TcModelWorkflowStore.class);
        private final TcModelDcopItemStore modelDcopItemStore = mock(TcModelDcopItemStore.class);
        private final JpaModelFlowQueryPort port = new JpaModelFlowQueryPort(
                modelWorkflowStore,
                modelDcopItemStore
        );
    }
}
