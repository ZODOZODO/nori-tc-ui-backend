package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.exception.DbDuplicateKeyException;
import com.nori.tc.db.core.model.store.TcModelMessageFlowDcopItemStore;
import com.nori.tc.db.core.model.store.TcModelMessageFlowStore;
import com.nori.tc.db.core.model.upsert.UpsertTcModelMessageFlowDcopItem;
import com.nori.tc.db.core.model.upsert.UpsertTcModelMessageFlow;
import com.nori.tc.db.domain.model.TcModelMessageFlowDcopItem;
import com.nori.tc.db.domain.model.TcModelMessageFlow;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.domain.model.UiMessageFlow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JpaModelFlowCommandPort} 의 write-side 계약을 검증합니다.
 *
 * <p>Collect 섹션 diff upsert 시나리오 (toAdd / toRemove / toKeep) 도 포함합니다.</p>
 */
class JpaModelFlowCommandPortTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-20T10:15:30+09:00");

    @Test
    @DisplayName("insertFlow는 messageFlowKey=null로 upsert를 호출하고 생성된 message_flow_key를 반환한다")
    void insertFlowDelegatesUpsertAndReturnsGeneratedKey() {
        final Fixture fixture = new Fixture();
        when(fixture.modelMessageFlowStore.upsert(any()))
                .thenReturn(workflow(123L, 101L, "WF_NEW", "S6F11", "TODO"));

        final long flowId = fixture.port.insertFlow(101L, "WF_NEW", "S6F11");

        assertEquals(123L, flowId);
        final ArgumentCaptor<UpsertTcModelMessageFlow> captor = ArgumentCaptor.forClass(UpsertTcModelMessageFlow.class);
        verify(fixture.modelMessageFlowStore).upsert(captor.capture());
        assertNull(captor.getValue().messageFlowKey());
        assertEquals(101L, captor.getValue().modelVersionKey());
        assertEquals("WF_NEW", captor.getValue().workflowName());
        assertEquals("S6F11", captor.getValue().messageName());
        // SPEC line 76 "신규 Flow 4 섹션 비어있음" 의 DB 표현 — action_name 은 NOT NULL 이므로 placeholder.
        assertEquals("TODO", captor.getValue().actionName());
        assertNull(captor.getValue().eventId());
        assertNull(captor.getValue().transactionId());
        assertNull(captor.getValue().workflowFilter());
        assertNull(captor.getValue().actionDataIndex());
    }

    @Test
    @DisplayName("insertFlow에서 DbDuplicateKeyException이 발생하면 UiConflictException으로 변환된다")
    void insertFlowThrowsConflictOnDuplicateKey() {
        final Fixture fixture = new Fixture();
        when(fixture.modelMessageFlowStore.upsert(any()))
                .thenThrow(new DbDuplicateKeyException("uk_tc_model_message_flow violated", new RuntimeException("dup")));

        assertThrows(UiConflictException.class,
                () -> fixture.port.insertFlow(101L, "WF_NEW", "S6F11"));
    }

    @Test
    @DisplayName("updateFlow는 flowId로 upsert를 호출하고 4섹션 모든 필드를 전달한다")
    void updateFlowDelegatesUpsertWithFullFields() {
        final Fixture fixture = new Fixture();
        when(fixture.modelMessageFlowStore.findByMessageFlowKey(77L))
                .thenReturn(Optional.of(workflow(77L, 101L, "WF_OLD", "S6F11", "actOld")));
        when(fixture.modelMessageFlowStore.upsert(any()))
                .thenReturn(workflow(77L, 101L, "WF_NEW", "S6F11", "actNew"));

        final UiMessageFlow request = new UiMessageFlow(
                77L,
                "WF_NEW",
                "S6F11",
                "EVT_1",
                "TX_1",
                "{\"op\":\"EQUAL\"}",
                "actNew",
                "{\"mdfTemplateName\":\"REQ\"}",
                List.of(),
                null
        );

        fixture.port.updateFlow(101L, 77L, request);

        final ArgumentCaptor<UpsertTcModelMessageFlow> captor = ArgumentCaptor.forClass(UpsertTcModelMessageFlow.class);
        verify(fixture.modelMessageFlowStore).upsert(captor.capture());
        assertEquals(77L, captor.getValue().messageFlowKey());
        assertEquals("WF_NEW", captor.getValue().workflowName());
        assertEquals("EVT_1", captor.getValue().eventId());
        assertEquals("TX_1", captor.getValue().transactionId());
        assertEquals("{\"op\":\"EQUAL\"}", captor.getValue().workflowFilter());
        assertEquals("actNew", captor.getValue().actionName());
        assertEquals("{\"mdfTemplateName\":\"REQ\"}", captor.getValue().actionDataIndex());
        // dcopItemKeys == null 이면 Collect 미갱신 — join store 비호출.
        verify(fixture.modelMessageFlowDcopItemStore, never()).findAllByMessageFlowKey(anyLong());
        verify(fixture.modelMessageFlowDcopItemStore, never()).upsert(any());
        verify(fixture.modelMessageFlowDcopItemStore, never())
                .deleteByMessageFlowKeyAndDcopItemKey(anyLong(), anyLong());
    }

    @Test
    @DisplayName("updateFlow Collect — toAdd / toRemove / toKeep diff 가 올바르게 처리된다")
    void updateFlow_CollectDiffUpsert() {
        final Fixture fixture = new Fixture();
        when(fixture.modelMessageFlowStore.findByMessageFlowKey(77L))
                .thenReturn(Optional.of(workflow(77L, 101L, "WF_OLD", "S6F11", "actOld")));
        when(fixture.modelMessageFlowStore.upsert(any()))
                .thenReturn(workflow(77L, 101L, "WF_OLD", "S6F11", "actOld"));
        // 기존 binding: 11, 22 → 신규 요청: 22, 33 → toAdd={33}, toRemove={11}, toKeep={22}
        when(fixture.modelMessageFlowDcopItemStore.findAllByMessageFlowKey(77L)).thenReturn(List.of(
                new TcModelMessageFlowDcopItem(801L, 77L, 11L, 101L, NOW),
                new TcModelMessageFlowDcopItem(802L, 77L, 22L, 101L, NOW)
        ));

        final UiMessageFlow request = new UiMessageFlow(
                77L,
                "WF_OLD",
                "S6F11",
                null, null, null,
                "actOld",
                null,
                List.of(),
                List.of(22L, 33L)
        );

        fixture.port.updateFlow(101L, 77L, request);

        // toAdd = {33}
        final ArgumentCaptor<UpsertTcModelMessageFlowDcopItem> upsertCaptor =
                ArgumentCaptor.forClass(UpsertTcModelMessageFlowDcopItem.class);
        verify(fixture.modelMessageFlowDcopItemStore).upsert(upsertCaptor.capture());
        assertNull(upsertCaptor.getValue().mfdiKey());
        assertEquals(77L, upsertCaptor.getValue().messageFlowKey());
        assertEquals(33L, upsertCaptor.getValue().dcopItemKey());
        assertEquals(101L, upsertCaptor.getValue().modelVersionKey());
        // toRemove = {11}
        verify(fixture.modelMessageFlowDcopItemStore).deleteByMessageFlowKeyAndDcopItemKey(77L, 11L);
        // toKeep = {22} — delete 호출 없음
        verify(fixture.modelMessageFlowDcopItemStore, never()).deleteByMessageFlowKeyAndDcopItemKey(77L, 22L);
    }

    @Test
    @DisplayName("updateFlow Collect — 빈 배열 요청은 기존 binding 전부 정리")
    void updateFlow_CollectEmpty_ClearsAllBindings() {
        final Fixture fixture = new Fixture();
        when(fixture.modelMessageFlowStore.findByMessageFlowKey(77L))
                .thenReturn(Optional.of(workflow(77L, 101L, "WF_OLD", "S6F11", "actOld")));
        when(fixture.modelMessageFlowStore.upsert(any()))
                .thenReturn(workflow(77L, 101L, "WF_OLD", "S6F11", "actOld"));
        when(fixture.modelMessageFlowDcopItemStore.findAllByMessageFlowKey(77L)).thenReturn(List.of(
                new TcModelMessageFlowDcopItem(801L, 77L, 11L, 101L, NOW),
                new TcModelMessageFlowDcopItem(802L, 77L, 22L, 101L, NOW)
        ));

        final UiMessageFlow request = new UiMessageFlow(
                77L, "WF_OLD", "S6F11", null, null, null, "actOld", null, List.of(), List.of()
        );

        fixture.port.updateFlow(101L, 77L, request);

        verify(fixture.modelMessageFlowDcopItemStore, never()).upsert(any());
        verify(fixture.modelMessageFlowDcopItemStore).deleteByMessageFlowKeyAndDcopItemKey(77L, 11L);
        verify(fixture.modelMessageFlowDcopItemStore).deleteByMessageFlowKeyAndDcopItemKey(77L, 22L);
    }

    @Test
    @DisplayName("updateFlow는 flowId가 존재하지 않으면 UiNotFoundException")
    void updateFlowThrowsNotFoundWhenFlowMissing() {
        final Fixture fixture = new Fixture();
        when(fixture.modelMessageFlowStore.findByMessageFlowKey(9999L)).thenReturn(Optional.empty());

        final UiMessageFlow request = new UiMessageFlow(
                9999L, "WF_X", "S6F11", null, null, null, "actX", null, List.of(), null
        );

        assertThrows(UiNotFoundException.class,
                () -> fixture.port.updateFlow(101L, 9999L, request));

        verify(fixture.modelMessageFlowStore, never()).upsert(any());
    }

    @Test
    @DisplayName("updateFlow는 workflow.modelVersionKey가 path 인자와 다르면 UiNotFoundException (다른 모델 row 보호)")
    void updateFlowThrowsNotFoundWhenModelVersionMismatch() {
        final Fixture fixture = new Fixture();
        when(fixture.modelMessageFlowStore.findByMessageFlowKey(77L))
                .thenReturn(Optional.of(workflow(77L, 999L, "WF_OTHER", "S6F11", "actX")));

        final UiMessageFlow request = new UiMessageFlow(
                77L, "WF_X", "S6F11", null, null, null, "actX", null, List.of(), null
        );

        assertThrows(UiNotFoundException.class,
                () -> fixture.port.updateFlow(101L, 77L, request));

        verify(fixture.modelMessageFlowStore, never()).upsert(any());
    }

    @Test
    @DisplayName("updateFlow에서 unique 위반시 UiConflictException으로 변환된다")
    void updateFlowThrowsConflictOnDuplicateKey() {
        final Fixture fixture = new Fixture();
        when(fixture.modelMessageFlowStore.findByMessageFlowKey(77L))
                .thenReturn(Optional.of(workflow(77L, 101L, "WF_OLD", "S6F11", "actOld")));
        when(fixture.modelMessageFlowStore.upsert(any()))
                .thenThrow(new DbDuplicateKeyException("uk_tc_model_message_flow violated", new RuntimeException("dup")));

        final UiMessageFlow request = new UiMessageFlow(
                77L, "WF_DUP", "S6F13", null, null, null, "actNew", null, List.of(), null
        );

        assertThrows(UiConflictException.class,
                () -> fixture.port.updateFlow(101L, 77L, request));
    }

    @Test
    @DisplayName("modelVersionKey <= 0 이면 UiBadRequestException")
    void rejectsInvalidModelVersionKey() {
        final Fixture fixture = new Fixture();

        assertThrows(com.nori.tc.ui.core.exception.UiBadRequestException.class,
                () -> fixture.port.insertFlow(0L, "WF", "MSG"));
        assertThrows(com.nori.tc.ui.core.exception.UiBadRequestException.class,
                () -> fixture.port.updateFlow(0L, 77L,
                        new UiMessageFlow(77L, "WF", "MSG", null, null, null, "act", null, List.of(), null)));
        verify(fixture.modelMessageFlowStore, never()).upsert(any());
        verify(fixture.modelMessageFlowStore, never()).findByMessageFlowKey(anyLong());
    }

    private static TcModelMessageFlow workflow(
            final long messageFlowKey,
            final long modelVersionKey,
            final String workflowName,
            final String messageName,
            final String actionName
    ) {
        return new TcModelMessageFlow(
                messageFlowKey,
                modelVersionKey,
                workflowName,
                messageName,
                null,
                null,
                null,
                actionName,
                null,
                NOW
        );
    }

    private static final class Fixture {

        private final TcModelMessageFlowStore modelMessageFlowStore = mock(TcModelMessageFlowStore.class);
        private final TcModelMessageFlowDcopItemStore modelMessageFlowDcopItemStore =
                mock(TcModelMessageFlowDcopItemStore.class);
        private final JpaModelFlowCommandPort port = new JpaModelFlowCommandPort(
                modelMessageFlowStore,
                modelMessageFlowDcopItemStore
        );
    }
}
