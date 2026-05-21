package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.model.store.TcModelDcopItemStore;
import com.nori.tc.db.core.model.store.TcModelMessageFlowDcopItemStore;
import com.nori.tc.db.core.model.store.TcModelMessageFlowStore;
import com.nori.tc.db.domain.common.model.DcopCollectionRule;
import com.nori.tc.db.domain.model.TcModelDcopItem;
import com.nori.tc.db.domain.model.TcModelMessageFlowDcopItem;
import com.nori.tc.db.domain.model.TcModelMessageFlow;
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
 *
 * <p>Collect 섹션은 N:M join table {@code tc_model_message_flow_dcop_item} 의 message_flow_key
 * 매칭으로 결정되므로, 같은 dcop_item 이 2 Flow 에 매핑된 상태에서도 각 Flow 조회 시
 * 자신의 binding 만 보여야 한다 (cross-Flow leak 부재).</p>
 */
class JpaModelFlowQueryPortTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-20T10:15:30+09:00");

    @Test
    @DisplayName("findFlowSummaries는 workflow row를 message_flow_key ASC로 정렬해 요약을 반환한다")
    void findFlowSummariesReturnsSortedByMessageFlowKeyAsc() {
        final Fixture fixture = new Fixture();
        when(fixture.modelMessageFlowStore.findAllByModelVersionKey(eq(101L), any(PageRequest.class)))
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
        when(fixture.modelMessageFlowStore.findAllByModelVersionKey(anyLong(), any(PageRequest.class)))
                .thenReturn(List.of());

        final List<UiMessageFlowSummary> summaries = fixture.port.findFlowSummaries(101L);

        assertTrue(summaries.isEmpty());
    }

    @Test
    @DisplayName("findFlowByMessageFlowKey는 SECS workflow 의 join row 에 매핑된 DCOP 2건만 묶어 반환한다")
    void findFlowByMessageFlowKeyReturnsDetailWithBoundDcops() {
        final Fixture fixture = new Fixture();
        final TcModelMessageFlow workflow = workflow(
                77L, 101L, "WF_A", "S6F11", "actA", "EVT_1", "TX_1", "{\"op\":\"EQUAL\"}"
        );
        when(fixture.modelMessageFlowStore.findByMessageFlowKey(77L)).thenReturn(Optional.of(workflow));
        when(fixture.modelMessageFlowDcopItemStore.findAllByMessageFlowKey(77L)).thenReturn(List.of(
                binding(901L, 77L, 101L, 101L),
                binding(902L, 77L, 103L, 101L)
        ));
        when(fixture.modelDcopItemStore.findAllByModelVersionKey(eq(101L), any(PageRequest.class)))
                .thenReturn(List.of(
                        dcopItem(101L, 101L, "DCOP_X"),
                        dcopItem(102L, 101L, "DCOP_Y"),
                        dcopItem(103L, 101L, "DCOP_Z")
                ))
                .thenReturn(List.of());

        final Optional<UiMessageFlow> detail = fixture.port.findFlowByMessageFlowKey(101L, 77L);

        assertTrue(detail.isPresent());
        assertEquals(77L, detail.get().flowId());
        assertEquals("WF_A", detail.get().workflowName());
        assertEquals("EVT_1", detail.get().eventId());
        assertEquals("TX_1", detail.get().transactionId());
        assertEquals(2, detail.get().dcopItems().size());
        assertEquals("DCOP_X", detail.get().dcopItems().get(0).dcopItemName());
        assertEquals("DCOP_Z", detail.get().dcopItems().get(1).dcopItemName());
        assertEquals(List.of(101L, 103L), detail.get().dcopItemKeys());
    }

    @Test
    @DisplayName("findFlowByMessageFlowKey N:M — 같은 dcop_item 이 2 Flow 에 매핑되어도 각 Flow 는 자신의 binding 만 본다")
    void findFlowByMessageFlowKey_N_M_DoesNotLeakAcrossFlows() {
        final Fixture fixture = new Fixture();
        final TcModelMessageFlow flowA = workflow(11L, 101L, "WF_A", "S6F11", "actA", null, null, null);
        final TcModelMessageFlow flowB = workflow(22L, 101L, "WF_B", "S6F13", "actB", null, null, null);

        when(fixture.modelMessageFlowStore.findByMessageFlowKey(11L)).thenReturn(Optional.of(flowA));
        when(fixture.modelMessageFlowStore.findByMessageFlowKey(22L)).thenReturn(Optional.of(flowB));

        // 같은 dcop_item (501) 이 두 Flow 모두에 binding 되어 있음 — N:M 핵심 시나리오.
        when(fixture.modelMessageFlowDcopItemStore.findAllByMessageFlowKey(11L)).thenReturn(List.of(
                binding(701L, 11L, 501L, 101L)
        ));
        when(fixture.modelMessageFlowDcopItemStore.findAllByMessageFlowKey(22L)).thenReturn(List.of(
                binding(702L, 22L, 501L, 101L),
                binding(703L, 22L, 502L, 101L)
        ));
        // 두 호출 (Flow A, Flow B) 모두 같은 dcop_item pool 을 반환. 페이지 사이즈 (500) 미만이라
        // 각 loadAllByPage 는 1 회만 호출되어 List of(...) 를 즉시 반환한다.
        when(fixture.modelDcopItemStore.findAllByModelVersionKey(eq(101L), any(PageRequest.class)))
                .thenReturn(List.of(
                        dcopItem(501L, 101L, "DCOP_SHARED"),
                        dcopItem(502L, 101L, "DCOP_B_ONLY")
                ));

        final Optional<UiMessageFlow> detailA = fixture.port.findFlowByMessageFlowKey(101L, 11L);
        final Optional<UiMessageFlow> detailB = fixture.port.findFlowByMessageFlowKey(101L, 22L);

        assertTrue(detailA.isPresent());
        assertEquals(List.of(501L), detailA.get().dcopItemKeys());
        assertEquals(1, detailA.get().dcopItems().size());
        assertEquals("DCOP_SHARED", detailA.get().dcopItems().get(0).dcopItemName());

        assertTrue(detailB.isPresent());
        assertEquals(List.of(501L, 502L), detailB.get().dcopItemKeys());
        assertEquals(2, detailB.get().dcopItems().size());
    }

    @Test
    @DisplayName("findFlowByMessageFlowKey 는 binding 이 0 건이면 dcopItems / dcopItemKeys 모두 빈 list")
    void findFlowByMessageFlowKey_NoBinding_ReturnsEmptyDcopList() {
        final Fixture fixture = new Fixture();
        when(fixture.modelMessageFlowStore.findByMessageFlowKey(77L))
                .thenReturn(Optional.of(workflow(77L, 101L, "WF_A", "S6F11", "actA", null, null, null)));
        when(fixture.modelMessageFlowDcopItemStore.findAllByMessageFlowKey(77L)).thenReturn(List.of());

        final Optional<UiMessageFlow> detail = fixture.port.findFlowByMessageFlowKey(101L, 77L);

        assertTrue(detail.isPresent());
        assertEquals(0, detail.get().dcopItems().size());
        assertEquals(0, detail.get().dcopItemKeys().size());
    }

    @Test
    @DisplayName("findFlowByMessageFlowKey는 workflow row가 없으면 Optional.empty를 반환한다")
    void findFlowByMessageFlowKeyReturnsEmptyWhenWorkflowMissing() {
        final Fixture fixture = new Fixture();
        when(fixture.modelMessageFlowStore.findByMessageFlowKey(9999L)).thenReturn(Optional.empty());

        final Optional<UiMessageFlow> detail = fixture.port.findFlowByMessageFlowKey(101L, 9999L);

        assertTrue(detail.isEmpty());
    }

    @Test
    @DisplayName("findFlowByMessageFlowKey는 workflow.modelVersionKey가 다르면 Optional.empty를 반환한다")
    void findFlowByMessageFlowKeyReturnsEmptyWhenModelVersionMismatch() {
        final Fixture fixture = new Fixture();
        // workflow 77 은 modelVersionKey=999 에 속함. 호출자는 modelVersionKey=101 로 조회 시도.
        when(fixture.modelMessageFlowStore.findByMessageFlowKey(77L))
                .thenReturn(Optional.of(workflow(77L, 999L, "WF_OTHER", "S6F11", "actX", null, null, null)));

        final Optional<UiMessageFlow> detail = fixture.port.findFlowByMessageFlowKey(101L, 77L);

        assertTrue(detail.isEmpty());
    }

    @Test
    @DisplayName("findFlowByMessageFlowKey는 Socket workflow (Trigger 3필드 null) 를 null 보존해 반환한다")
    void findFlowByMessageFlowKeyPreservesSocketTriggerNulls() {
        final Fixture fixture = new Fixture();
        final TcModelMessageFlow socket = workflow(77L, 101L, "WF_S", "SOCK_MSG", "actS", null, null, null);
        when(fixture.modelMessageFlowStore.findByMessageFlowKey(77L)).thenReturn(Optional.of(socket));
        when(fixture.modelMessageFlowDcopItemStore.findAllByMessageFlowKey(77L)).thenReturn(List.of());

        final Optional<UiMessageFlow> detail = fixture.port.findFlowByMessageFlowKey(101L, 77L);

        assertTrue(detail.isPresent());
        assertNull(detail.get().eventId());
        assertNull(detail.get().transactionId());
        assertNull(detail.get().workflowFilter());
        assertEquals(0, detail.get().dcopItems().size());
    }

    private static TcModelMessageFlow workflow(
            final long messageFlowKey,
            final long modelVersionKey,
            final String workflowName,
            final String messageName,
            final String actionName,
            final String eventId,
            final String transactionId,
            final String workflowFilter
    ) {
        return new TcModelMessageFlow(
                messageFlowKey,
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
            final String dcopItemName
    ) {
        return new TcModelDcopItem(
                dcopItemKey,
                modelVersionKey,
                dcopItemName,
                null,
                null,
                DcopCollectionRule.LAST,
                null,
                1,
                NOW
        );
    }

    private static TcModelMessageFlowDcopItem binding(
            final long mfdiKey,
            final long messageFlowKey,
            final long dcopItemKey,
            final long modelVersionKey
    ) {
        return new TcModelMessageFlowDcopItem(
                mfdiKey,
                messageFlowKey,
                dcopItemKey,
                modelVersionKey,
                NOW
        );
    }

    private static final class Fixture {

        private final TcModelMessageFlowStore modelMessageFlowStore = mock(TcModelMessageFlowStore.class);
        private final TcModelDcopItemStore modelDcopItemStore = mock(TcModelDcopItemStore.class);
        private final TcModelMessageFlowDcopItemStore modelMessageFlowDcopItemStore =
                mock(TcModelMessageFlowDcopItemStore.class);
        private final JpaModelFlowQueryPort port = new JpaModelFlowQueryPort(
                modelMessageFlowStore,
                modelDcopItemStore,
                modelMessageFlowDcopItemStore
        );
    }
}
