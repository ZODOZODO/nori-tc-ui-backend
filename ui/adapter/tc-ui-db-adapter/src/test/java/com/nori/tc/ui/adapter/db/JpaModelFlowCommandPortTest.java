package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.exception.DbDuplicateKeyException;
import com.nori.tc.db.core.model.store.TcModelWorkflowStore;
import com.nori.tc.db.core.model.upsert.UpsertTcModelWorkflow;
import com.nori.tc.db.domain.model.TcModelWorkflow;
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
 */
class JpaModelFlowCommandPortTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-20T10:15:30+09:00");

    @Test
    @DisplayName("insertFlow는 workflowKey=null로 upsert를 호출하고 생성된 workflow_key를 반환한다")
    void insertFlowDelegatesUpsertAndReturnsGeneratedKey() {
        final Fixture fixture = new Fixture();
        when(fixture.modelWorkflowStore.upsert(any()))
                .thenReturn(workflow(123L, 101L, "WF_NEW", "S6F11", "TODO"));

        final long flowId = fixture.port.insertFlow(101L, "WF_NEW", "S6F11");

        assertEquals(123L, flowId);
        final ArgumentCaptor<UpsertTcModelWorkflow> captor = ArgumentCaptor.forClass(UpsertTcModelWorkflow.class);
        verify(fixture.modelWorkflowStore).upsert(captor.capture());
        assertNull(captor.getValue().workflowKey());
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
        when(fixture.modelWorkflowStore.upsert(any()))
                .thenThrow(new DbDuplicateKeyException("uk_tc_model_workflow violated", new RuntimeException("dup")));

        assertThrows(UiConflictException.class,
                () -> fixture.port.insertFlow(101L, "WF_NEW", "S6F11"));
    }

    @Test
    @DisplayName("updateFlow는 flowId로 upsert를 호출하고 4섹션 모든 필드를 전달한다")
    void updateFlowDelegatesUpsertWithFullFields() {
        final Fixture fixture = new Fixture();
        when(fixture.modelWorkflowStore.findByWorkflowKey(77L))
                .thenReturn(Optional.of(workflow(77L, 101L, "WF_OLD", "S6F11", "actOld")));
        when(fixture.modelWorkflowStore.upsert(any()))
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
                List.of()
        );

        fixture.port.updateFlow(101L, 77L, request);

        final ArgumentCaptor<UpsertTcModelWorkflow> captor = ArgumentCaptor.forClass(UpsertTcModelWorkflow.class);
        verify(fixture.modelWorkflowStore).upsert(captor.capture());
        assertEquals(77L, captor.getValue().workflowKey());
        assertEquals("WF_NEW", captor.getValue().workflowName());
        assertEquals("EVT_1", captor.getValue().eventId());
        assertEquals("TX_1", captor.getValue().transactionId());
        assertEquals("{\"op\":\"EQUAL\"}", captor.getValue().workflowFilter());
        assertEquals("actNew", captor.getValue().actionName());
        assertEquals("{\"mdfTemplateName\":\"REQ\"}", captor.getValue().actionDataIndex());
    }

    @Test
    @DisplayName("updateFlow는 flowId가 존재하지 않으면 UiNotFoundException")
    void updateFlowThrowsNotFoundWhenFlowMissing() {
        final Fixture fixture = new Fixture();
        when(fixture.modelWorkflowStore.findByWorkflowKey(9999L)).thenReturn(Optional.empty());

        final UiMessageFlow request = new UiMessageFlow(
                9999L, "WF_X", "S6F11", null, null, null, "actX", null, List.of()
        );

        assertThrows(UiNotFoundException.class,
                () -> fixture.port.updateFlow(101L, 9999L, request));

        verify(fixture.modelWorkflowStore, never()).upsert(any());
    }

    @Test
    @DisplayName("updateFlow는 workflow.modelVersionKey가 path 인자와 다르면 UiNotFoundException (다른 모델 row 보호)")
    void updateFlowThrowsNotFoundWhenModelVersionMismatch() {
        final Fixture fixture = new Fixture();
        when(fixture.modelWorkflowStore.findByWorkflowKey(77L))
                .thenReturn(Optional.of(workflow(77L, 999L, "WF_OTHER", "S6F11", "actX")));

        final UiMessageFlow request = new UiMessageFlow(
                77L, "WF_X", "S6F11", null, null, null, "actX", null, List.of()
        );

        assertThrows(UiNotFoundException.class,
                () -> fixture.port.updateFlow(101L, 77L, request));

        verify(fixture.modelWorkflowStore, never()).upsert(any());
    }

    @Test
    @DisplayName("updateFlow에서 unique 위반시 UiConflictException으로 변환된다")
    void updateFlowThrowsConflictOnDuplicateKey() {
        final Fixture fixture = new Fixture();
        when(fixture.modelWorkflowStore.findByWorkflowKey(77L))
                .thenReturn(Optional.of(workflow(77L, 101L, "WF_OLD", "S6F11", "actOld")));
        when(fixture.modelWorkflowStore.upsert(any()))
                .thenThrow(new DbDuplicateKeyException("uk_tc_model_workflow violated", new RuntimeException("dup")));

        final UiMessageFlow request = new UiMessageFlow(
                77L, "WF_DUP", "S6F13", null, null, null, "actNew", null, List.of()
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
                        new UiMessageFlow(77L, "WF", "MSG", null, null, null, "act", null, List.of())));
        verify(fixture.modelWorkflowStore, never()).upsert(any());
        verify(fixture.modelWorkflowStore, never()).findByWorkflowKey(anyLong());
    }

    private static TcModelWorkflow workflow(
            final long workflowKey,
            final long modelVersionKey,
            final String workflowName,
            final String messageName,
            final String actionName
    ) {
        return new TcModelWorkflow(
                workflowKey,
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

        private final TcModelWorkflowStore modelWorkflowStore = mock(TcModelWorkflowStore.class);
        private final JpaModelFlowCommandPort port = new JpaModelFlowCommandPort(modelWorkflowStore);
    }
}
