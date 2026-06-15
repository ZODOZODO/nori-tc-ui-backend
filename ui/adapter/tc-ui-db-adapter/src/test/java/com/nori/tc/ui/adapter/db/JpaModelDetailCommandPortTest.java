package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.model.store.TcModelDcopItemStore;
import com.nori.tc.db.core.model.store.TcModelEventIdStore;
import com.nori.tc.db.core.model.store.TcModelMdfStore;
import com.nori.tc.db.core.model.store.TcModelMesMessageStore;
import com.nori.tc.db.core.model.store.TcModelParamStore;
import com.nori.tc.db.core.model.store.TcModelReportIdStore;
import com.nori.tc.db.core.model.store.TcModelSecsMessageStore;
import com.nori.tc.db.core.model.store.TcModelSocketMessageStore;
import com.nori.tc.db.core.model.store.TcModelStore;
import com.nori.tc.db.core.model.store.TcModelVariableIdStore;
import com.nori.tc.db.core.model.store.TcModelMessageFlowStore;
import com.nori.tc.db.core.model.store.TcModelPlcGroupStore;
import com.nori.tc.db.core.model.store.TcModelPlcRegisterStore;
import com.nori.tc.db.core.model.upsert.UpsertTcModelDcopItem;
import com.nori.tc.db.core.model.upsert.UpsertTcModelMdf;
import com.nori.tc.db.core.model.upsert.UpsertTcModelMessageFlow;
import com.nori.tc.db.core.model.upsert.UpsertTcModelPlcGroup;
import com.nori.tc.db.core.model.upsert.UpsertTcModelPlcRegister;
import com.nori.tc.db.domain.common.model.DcopCollectionRule;
import com.nori.tc.db.domain.common.model.ModelStatus;
import com.nori.tc.db.domain.common.model.PlcArea;
import com.nori.tc.db.domain.common.model.PlcDataType;
import com.nori.tc.db.domain.common.model.ProtocolType;
import com.nori.tc.db.domain.model.TcModel;
import com.nori.tc.db.domain.model.TcModelDcopItem;
import com.nori.tc.db.domain.model.TcModelMdf;
import com.nori.tc.db.domain.model.TcModelMessageFlow;
import com.nori.tc.db.domain.model.TcModelPlcGroup;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.port.db.ModelDetailCommandPort;
import com.nori.tc.ui.domain.model.UiModelMdf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JpaModelDetailCommandPort}의 MDF 업로드 검증과 저장 계약을 검증합니다.
 */
class JpaModelDetailCommandPortTest {

    @Test
    @DisplayName("saveMdf는 UTF-8 XML 검증 후 기존 mdf_key를 유지하여 저장합니다")
    void saveMdfValidatesAndUpsertsExistingMdf() {
        final Fixture fixture = new Fixture();
        final TcModel model = model(101L);
        final TcModelMdf existing = mdf(55L, 101L, "OLD_MDF", "<mdf><old/></mdf>");
        final String xml = "<mdf><message name=\"REQ\">OK</message></mdf>";

        when(fixture.modelStore.findByModelVersionKey(101L)).thenReturn(Optional.of(model));
        when(fixture.modelMdfStore.findByModelVersionKey(101L)).thenReturn(Optional.of(existing));
        when(fixture.modelMdfStore.upsert(any())).thenReturn(mdf(55L, 101L, "REQ_MDF", xml));

        final UiModelMdf saved = fixture.port.saveMdf(101L, "REQ_MDF", xml.getBytes(StandardCharsets.UTF_8));

        final ArgumentCaptor<UpsertTcModelMdf> captor = ArgumentCaptor.forClass(UpsertTcModelMdf.class);
        verify(fixture.modelMdfStore).upsert(captor.capture());

        assertEquals(55L, captor.getValue().mdfKey());
        assertEquals("REQ_MDF", captor.getValue().mdfName());
        assertEquals(xml, new String(captor.getValue().mdfFile(), StandardCharsets.UTF_8));
        assertEquals("REQ_MDF", saved.mdfName());
    }

    @Test
    @DisplayName("saveMdf는 잘못된 XML 형식을 400으로 거부합니다")
    void saveMdfRejectsMalformedXml() {
        final Fixture fixture = new Fixture();

        when(fixture.modelStore.findByModelVersionKey(101L)).thenReturn(Optional.of(model(101L)));

        final UiBadRequestException exception = assertThrows(
                UiBadRequestException.class,
                () -> fixture.port.saveMdf(
                        101L,
                        "BROKEN_MDF",
                        "<mdf><message name=\"A\"></mdf>".getBytes(StandardCharsets.UTF_8)
                )
        );

        assertEquals("MDF XML 형식이 올바르지 않습니다.", exception.getMessage());
        verify(fixture.modelMdfStore, never()).upsert(any());
    }

    @Test
    @DisplayName("saveMdf는 대상 모델이 없으면 404를 반환합니다")
    void saveMdfRejectsMissingModel() {
        final Fixture fixture = new Fixture();

        when(fixture.modelStore.findByModelVersionKey(999L)).thenReturn(Optional.empty());

        final UiNotFoundException exception = assertThrows(
                UiNotFoundException.class,
                () -> fixture.port.saveMdf(
                        999L,
                        "REQ_MDF",
                        "<mdf><message name=\"REQ\">OK</message></mdf>".getBytes(StandardCharsets.UTF_8)
                )
        );

        assertEquals("모델을 찾을 수 없습니다.", exception.getMessage());
        verify(fixture.modelMdfStore, never()).upsert(any());
    }

    // =================================================================================
    // workflow 노드 id-aware upsert 마이그레이션 검증
    // =================================================================================

    @Test
    @DisplayName("workflow 저장 시 row.id가 null이면 신규 INSERT (messageFlowKey=null upsert, DELETE 없음)")
    void replaceWorkflows_idNull_insertsAsNew() {
        final Fixture fixture = new Fixture();
        when(fixture.modelStore.findByModelVersionKey(101L)).thenReturn(Optional.of(model(101L)));
        when(fixture.modelMessageFlowStore.findAllByModelVersionKey(anyLong(), any(PageRequest.class)))
                .thenReturn(List.of());

        fixture.port.saveDetailRows(
                101L,
                "workflow",
                List.of(new ModelDetailCommandPort.DetailRowCommand(
                        null,
                        Arrays.asList("WF_NEW", "S6F11", "EVT_1", "TX_1", null, "actNew", null)
                ))
        );

        final ArgumentCaptor<UpsertTcModelMessageFlow> captor = ArgumentCaptor.forClass(UpsertTcModelMessageFlow.class);
        verify(fixture.modelMessageFlowStore).upsert(captor.capture());
        assertNull(captor.getValue().messageFlowKey());
        assertEquals("WF_NEW", captor.getValue().workflowName());
        assertEquals("S6F11", captor.getValue().messageName());
        verify(fixture.modelMessageFlowStore, never()).deleteByMessageFlowKey(anyLong());
    }

    @Test
    @DisplayName("workflow 저장 시 row.id가 기존 workflow_key와 일치하면 UPDATE (그 키로 upsert)")
    void replaceWorkflows_idMatchesExisting_updatesByKey() {
        final Fixture fixture = new Fixture();
        when(fixture.modelStore.findByModelVersionKey(101L)).thenReturn(Optional.of(model(101L)));
        when(fixture.modelMessageFlowStore.findAllByModelVersionKey(anyLong(), any(PageRequest.class)))
                .thenReturn(List.of(workflow(77L, 101L, "WF_OLD", "S6F11", "actOld")));

        fixture.port.saveDetailRows(
                101L,
                "workflow",
                List.of(new ModelDetailCommandPort.DetailRowCommand(
                        "77",
                        Arrays.asList("WF_OLD", "S6F11", "EVT_X", "TX_X", null, "actUpdated", null)
                ))
        );

        final ArgumentCaptor<UpsertTcModelMessageFlow> captor = ArgumentCaptor.forClass(UpsertTcModelMessageFlow.class);
        verify(fixture.modelMessageFlowStore).upsert(captor.capture());
        assertEquals(77L, captor.getValue().messageFlowKey());
        assertEquals("actUpdated", captor.getValue().actionName());
        verify(fixture.modelMessageFlowStore, never()).deleteByMessageFlowKey(anyLong());
    }

    @Test
    @DisplayName("workflow 저장 시 응답에 없는 기존 row는 deleteByMessageFlowKey 1회 호출")
    void replaceWorkflows_existingNotInRequest_deletesByKey() {
        final Fixture fixture = new Fixture();
        when(fixture.modelStore.findByModelVersionKey(101L)).thenReturn(Optional.of(model(101L)));
        when(fixture.modelMessageFlowStore.findAllByModelVersionKey(anyLong(), any(PageRequest.class)))
                .thenReturn(List.of(
                        workflow(77L, 101L, "WF_KEEP", "S6F11", "actKeep"),
                        workflow(88L, 101L, "WF_REMOVED", "S6F13", "actGone")
                ));

        fixture.port.saveDetailRows(
                101L,
                "workflow",
                List.of(new ModelDetailCommandPort.DetailRowCommand(
                        "77",
                        Arrays.asList("WF_KEEP", "S6F11", null, null, null, "actKeep", null)
                ))
        );

        verify(fixture.modelMessageFlowStore).deleteByMessageFlowKey(88L);
        verify(fixture.modelMessageFlowStore, never()).deleteByMessageFlowKey(77L);
    }

    @Test
    @DisplayName("workflow 저장 시 row.id가 기존 row와 매칭되지 않으면 400을 반환")
    void replaceWorkflows_idDoesNotMatchExisting_throwsBadRequest() {
        final Fixture fixture = new Fixture();
        when(fixture.modelStore.findByModelVersionKey(101L)).thenReturn(Optional.of(model(101L)));
        when(fixture.modelMessageFlowStore.findAllByModelVersionKey(anyLong(), any(PageRequest.class)))
                .thenReturn(List.of(workflow(77L, 101L, "WF_OLD", "S6F11", "actOld")));

        final UiBadRequestException exception = assertThrows(
                UiBadRequestException.class,
                () -> fixture.port.saveDetailRows(
                        101L,
                        "workflow",
                        List.of(new ModelDetailCommandPort.DetailRowCommand(
                                "9999",
                                Arrays.asList("WF_NEW", "S6F11", null, null, null, "actNew", null)
                        ))
                )
        );

        assertEquals("존재하지 않는 workflow row id 입니다.", exception.getMessage());
        verify(fixture.modelMessageFlowStore, never()).upsert(any());
        verify(fixture.modelMessageFlowStore, never()).deleteByMessageFlowKey(anyLong());
    }

    @Test
    @DisplayName("workflow 저장 시 row.id가 숫자가 아니면 400을 반환")
    void replaceWorkflows_idNotNumeric_throwsBadRequest() {
        final Fixture fixture = new Fixture();
        when(fixture.modelStore.findByModelVersionKey(101L)).thenReturn(Optional.of(model(101L)));
        when(fixture.modelMessageFlowStore.findAllByModelVersionKey(anyLong(), any(PageRequest.class)))
                .thenReturn(List.of());

        final UiBadRequestException exception = assertThrows(
                UiBadRequestException.class,
                () -> fixture.port.saveDetailRows(
                        101L,
                        "workflow",
                        List.of(new ModelDetailCommandPort.DetailRowCommand(
                                "not-a-number",
                                Arrays.asList("WF_NEW", "S6F11", null, null, null, "actNew", null)
                        ))
                )
        );

        assertEquals("workflow row id 형식이 올바르지 않습니다.", exception.getMessage());
        verify(fixture.modelMessageFlowStore, never()).upsert(any());
    }

    // =================================================================================
    // dcop-itemes 노드 id-aware upsert 마이그레이션 검증
    // =================================================================================

    @Test
    @DisplayName("dcop 저장 시 row.id가 null이면 신규 INSERT (dcopItemKey=null upsert, DELETE 없음)")
    void replaceDcopItems_idNull_insertsAsNew() {
        final Fixture fixture = new Fixture();
        when(fixture.modelStore.findByModelVersionKey(101L)).thenReturn(Optional.of(model(101L)));
        when(fixture.modelDcopItemStore.findAllByModelVersionKey(anyLong(), any(PageRequest.class)))
                .thenReturn(List.of());

        fixture.port.saveDetailRows(
                101L,
                "dcop-itemes",
                List.of(new ModelDetailCommandPort.DetailRowCommand(
                        null,
                        Arrays.asList("DCOP_NEW", "EVT_1", "VAR_1", "LAST", null, "1")
                ))
        );

        final ArgumentCaptor<UpsertTcModelDcopItem> captor = ArgumentCaptor.forClass(UpsertTcModelDcopItem.class);
        verify(fixture.modelDcopItemStore).upsert(captor.capture());
        assertNull(captor.getValue().dcopItemKey());
        assertEquals("DCOP_NEW", captor.getValue().dcopItemName());
        assertEquals("EVT_1", captor.getValue().eventId());
        assertEquals("VAR_1", captor.getValue().variableId());
        verify(fixture.modelDcopItemStore, never()).deleteByDcopItemKey(anyLong());
    }

    @Test
    @DisplayName("dcop 저장 시 row.id가 기존 dcop_item_key와 일치하면 UPDATE (그 키로 upsert)")
    void replaceDcopItems_idMatchesExisting_updatesByKey() {
        final Fixture fixture = new Fixture();
        when(fixture.modelStore.findByModelVersionKey(101L)).thenReturn(Optional.of(model(101L)));
        when(fixture.modelDcopItemStore.findAllByModelVersionKey(anyLong(), any(PageRequest.class)))
                .thenReturn(List.of(dcopItem(55L, 101L, "DCOP_OLD")));

        fixture.port.saveDetailRows(
                101L,
                "dcop-itemes",
                List.of(new ModelDetailCommandPort.DetailRowCommand(
                        "55",
                        Arrays.asList("DCOP_OLD", "EVT_2", "VAR_2", "FIRST", null, "2")
                ))
        );

        final ArgumentCaptor<UpsertTcModelDcopItem> captor = ArgumentCaptor.forClass(UpsertTcModelDcopItem.class);
        verify(fixture.modelDcopItemStore).upsert(captor.capture());
        assertEquals(55L, captor.getValue().dcopItemKey());
        assertEquals("DCOP_OLD", captor.getValue().dcopItemName());
        assertEquals("EVT_2", captor.getValue().eventId());
        assertEquals("VAR_2", captor.getValue().variableId());
        verify(fixture.modelDcopItemStore, never()).deleteByDcopItemKey(anyLong());
    }

    @Test
    @DisplayName("dcop 저장 시 응답에 없는 기존 row는 deleteByDcopItemKey 1회 호출")
    void replaceDcopItems_existingNotInRequest_deletesByKey() {
        final Fixture fixture = new Fixture();
        when(fixture.modelStore.findByModelVersionKey(101L)).thenReturn(Optional.of(model(101L)));
        when(fixture.modelDcopItemStore.findAllByModelVersionKey(anyLong(), any(PageRequest.class)))
                .thenReturn(List.of(
                        dcopItem(55L, 101L, "DCOP_KEEP"),
                        dcopItem(66L, 101L, "DCOP_REMOVED")
                ));

        fixture.port.saveDetailRows(
                101L,
                "dcop-itemes",
                List.of(new ModelDetailCommandPort.DetailRowCommand(
                        "55",
                        Arrays.asList("DCOP_KEEP", null, null, null, null, null)
                ))
        );

        verify(fixture.modelDcopItemStore).deleteByDcopItemKey(66L);
        verify(fixture.modelDcopItemStore, never()).deleteByDcopItemKey(55L);
    }

    @Test
    @DisplayName("dcop 저장 시 row.id가 기존 row와 매칭되지 않으면 400을 반환")
    void replaceDcopItems_idDoesNotMatchExisting_throwsBadRequest() {
        final Fixture fixture = new Fixture();
        when(fixture.modelStore.findByModelVersionKey(101L)).thenReturn(Optional.of(model(101L)));
        when(fixture.modelDcopItemStore.findAllByModelVersionKey(anyLong(), any(PageRequest.class)))
                .thenReturn(List.of(dcopItem(55L, 101L, "DCOP_OLD")));

        final UiBadRequestException exception = assertThrows(
                UiBadRequestException.class,
                () -> fixture.port.saveDetailRows(
                        101L,
                        "dcop-itemes",
                        List.of(new ModelDetailCommandPort.DetailRowCommand(
                                "9999",
                                Arrays.asList("DCOP_OLD", null, null, null, null, null)
                        ))
                )
        );

        assertEquals("존재하지 않는 dcop row id 입니다.", exception.getMessage());
        verify(fixture.modelDcopItemStore, never()).upsert(any());
        verify(fixture.modelDcopItemStore, never()).deleteByDcopItemKey(anyLong());
    }

    @Test
    @DisplayName("dcop 저장 시 row.id가 숫자가 아니면 400을 반환")
    void replaceDcopItems_idNotNumeric_throwsBadRequest() {
        final Fixture fixture = new Fixture();
        when(fixture.modelStore.findByModelVersionKey(101L)).thenReturn(Optional.of(model(101L)));
        when(fixture.modelDcopItemStore.findAllByModelVersionKey(anyLong(), any(PageRequest.class)))
                .thenReturn(List.of());

        final UiBadRequestException exception = assertThrows(
                UiBadRequestException.class,
                () -> fixture.port.saveDetailRows(
                        101L,
                        "dcop-itemes",
                        List.of(new ModelDetailCommandPort.DetailRowCommand(
                                "not-a-number",
                                Arrays.asList("DCOP_NEW", null, null, null, null, null)
                        ))
                )
        );

        assertEquals("dcop row id 형식이 올바르지 않습니다.", exception.getMessage());
        verify(fixture.modelDcopItemStore, never()).upsert(any());
    }

    // =================================================================================
    // plc-group / plc-register 노드 저장 검증
    // =================================================================================

    @Test
    @DisplayName("plc-group 저장은 message_name 자연키로 upsert(groupKey=null)하고 요청에 없는 그룹은 삭제")
    void replacePlcGroups_naturalKeyUpsert_deletesAbsent() {
        final Fixture fixture = new Fixture();
        when(fixture.modelStore.findByModelVersionKey(101L)).thenReturn(Optional.of(model(101L)));
        when(fixture.modelPlcGroupStore.findAllByModelVersionKey(101L)).thenReturn(List.of(
                plcGroup(7L, 101L, "STATUS_REPORT"),
                plcGroup(8L, 101L, "OLD_GROUP")
        ));

        fixture.port.saveDetailRows(
                101L,
                "plc-group",
                List.of(new ModelDetailCommandPort.DetailRowCommand(
                        "plcgroup:7",
                        Arrays.asList("STATUS_REPORT", "HOLDING_REGISTER", "0", "10", "1000", null, "desc")
                ))
        );

        final ArgumentCaptor<UpsertTcModelPlcGroup> captor = ArgumentCaptor.forClass(UpsertTcModelPlcGroup.class);
        verify(fixture.modelPlcGroupStore).upsert(captor.capture());
        assertNull(captor.getValue().groupKey());
        assertEquals("STATUS_REPORT", captor.getValue().messageName());
        assertEquals(PlcArea.HOLDING_REGISTER, captor.getValue().area());
        assertEquals(10, captor.getValue().readCount());
        assertEquals(1000, captor.getValue().pollPeriodMs());
        verify(fixture.modelPlcGroupStore).deleteByGroupKey(8L);
        verify(fixture.modelPlcGroupStore, never()).deleteByGroupKey(7L);
    }

    @Test
    @DisplayName("plc-group 저장 시 message_name이 중복되면 400으로 거부")
    void replacePlcGroups_duplicateMessageName_throwsBadRequest() {
        final Fixture fixture = new Fixture();
        when(fixture.modelStore.findByModelVersionKey(101L)).thenReturn(Optional.of(model(101L)));

        final UiBadRequestException exception = assertThrows(
                UiBadRequestException.class,
                () -> fixture.port.saveDetailRows(
                        101L,
                        "plc-group",
                        List.of(
                                new ModelDetailCommandPort.DetailRowCommand(
                                        null,
                                        Arrays.asList("DUP", "HOLDING_REGISTER", "0", "10", "1000", null, null)),
                                new ModelDetailCommandPort.DetailRowCommand(
                                        null,
                                        Arrays.asList("DUP", "HOLDING_REGISTER", "10", "5", "1000", null, null))
                        )
                )
        );

        assertTrue(exception.getMessage().contains("중복"));
    }

    @Test
    @DisplayName("plc-group 저장 시 message_name이 eventType 네이밍(UPPER_SNAKE_CASE)을 어기면 400으로 거부")
    void replacePlcGroups_invalidMessageNameFormat_throwsBadRequest() {
        final Fixture fixture = new Fixture();
        when(fixture.modelStore.findByModelVersionKey(101L)).thenReturn(Optional.of(model(101L)));

        final UiBadRequestException exception = assertThrows(
                UiBadRequestException.class,
                () -> fixture.port.saveDetailRows(
                        101L,
                        "plc-group",
                        List.of(new ModelDetailCommandPort.DetailRowCommand(
                                null,
                                Arrays.asList("status_report", "HOLDING_REGISTER", "0", "10", "1000", null, null)))
                )
        );

        assertTrue(exception.getMessage().contains("형식"));
        verify(fixture.modelPlcGroupStore, never()).upsert(any());
    }

    @Test
    @DisplayName("plc-register 저장 시 word_offset이 read_count 윈도우 밖이면 400으로 거부(삭제/upsert 없음)")
    void replacePlcRegisters_offsetOutsideWindow_throwsBadRequest() {
        final Fixture fixture = new Fixture();
        when(fixture.modelStore.findByModelVersionKey(101L)).thenReturn(Optional.of(model(101L)));
        when(fixture.modelPlcGroupStore.findAllByModelVersionKey(101L)).thenReturn(List.of(
                plcGroupFull(7L, 101L, "STATUS_REPORT", PlcArea.HOLDING_REGISTER, 0, 2, 1000)
        ));

        final UiBadRequestException exception = assertThrows(
                UiBadRequestException.class,
                () -> fixture.port.saveDetailRows(
                        101L,
                        "plc-register",
                        List.of(new ModelDetailCommandPort.DetailRowCommand(
                                null,
                                Arrays.asList("STATUS_REPORT", "5", "TEMP", "INT16",
                                        null, null, null, null, null, null, null, null, null)
                        ))
                )
        );

        assertTrue(exception.getMessage().contains("레이아웃 검증 실패"));
        verify(fixture.modelPlcRegisterStore, never()).upsert(any());
        verify(fixture.modelPlcRegisterStore, never()).deleteAllByGroupKey(anyLong());
    }

    @Test
    @DisplayName("plc-register 저장 시 존재하지 않는 그룹을 참조하면 400으로 거부")
    void replacePlcRegisters_unknownGroup_throwsBadRequest() {
        final Fixture fixture = new Fixture();
        when(fixture.modelStore.findByModelVersionKey(101L)).thenReturn(Optional.of(model(101L)));
        when(fixture.modelPlcGroupStore.findAllByModelVersionKey(101L)).thenReturn(List.of());

        final UiBadRequestException exception = assertThrows(
                UiBadRequestException.class,
                () -> fixture.port.saveDetailRows(
                        101L,
                        "plc-register",
                        List.of(new ModelDetailCommandPort.DetailRowCommand(
                                null,
                                Arrays.asList("NO_SUCH_GROUP", "0", "VAR", "INT16",
                                        null, null, null, null, null, null, null, null, null)
                        ))
                )
        );

        assertTrue(exception.getMessage().contains("존재하지 않는 PLC 그룹"));
        verify(fixture.modelPlcRegisterStore, never()).upsert(any());
    }

    @Test
    @DisplayName("plc-register 유효 저장은 그룹 레지스터를 비우고(deleteAllByGroupKey) 재삽입(upsert)")
    void replacePlcRegisters_valid_deletesThenUpserts() {
        final Fixture fixture = new Fixture();
        when(fixture.modelStore.findByModelVersionKey(101L)).thenReturn(Optional.of(model(101L)));
        when(fixture.modelPlcGroupStore.findAllByModelVersionKey(101L)).thenReturn(List.of(
                plcGroupFull(7L, 101L, "STATUS_REPORT", PlcArea.HOLDING_REGISTER, 0, 10, 1000)
        ));

        fixture.port.saveDetailRows(
                101L,
                "plc-register",
                List.of(new ModelDetailCommandPort.DetailRowCommand(
                        null,
                        Arrays.asList("STATUS_REPORT", "1", "TEMP", "INT16",
                                null, null, null, null, null, "0.1", null, null, "temp var")
                ))
        );

        verify(fixture.modelPlcRegisterStore).deleteAllByGroupKey(7L);
        final ArgumentCaptor<UpsertTcModelPlcRegister> captor = ArgumentCaptor.forClass(UpsertTcModelPlcRegister.class);
        verify(fixture.modelPlcRegisterStore).upsert(captor.capture());
        assertNull(captor.getValue().registerKey());
        assertEquals(7L, captor.getValue().groupKey());
        assertEquals(1, captor.getValue().wordOffset());
        assertEquals("TEMP", captor.getValue().variableName());
        assertEquals(PlcDataType.INT16, captor.getValue().dataType());
        assertEquals(0.1, captor.getValue().scale(), 0.0);
    }

    private static TcModelPlcGroup plcGroup(final long groupKey, final long modelVersionKey, final String messageName) {
        return plcGroupFull(groupKey, modelVersionKey, messageName, PlcArea.HOLDING_REGISTER, 0, 10, 1000);
    }

    private static TcModelPlcGroup plcGroupFull(
            final long groupKey,
            final long modelVersionKey,
            final String messageName,
            final PlcArea area,
            final int startAddress,
            final int readCount,
            final int pollPeriodMs
    ) {
        return new TcModelPlcGroup(
                groupKey,
                modelVersionKey,
                messageName,
                area,
                startAddress,
                readCount,
                null,
                pollPeriodMs,
                null,
                OffsetDateTime.parse("2026-03-13T10:15:30+09:00")
        );
    }

    private static TcModel model(final long modelVersionKey) {
        final OffsetDateTime now = OffsetDateTime.parse("2026-03-13T10:15:30+09:00");
        return new TcModel(
                modelVersionKey,
                11L,
                "MODEL-A",
                null,
                "EDIT",
                ProtocolType.SECS,
                ModelStatus.OPERATE,
                "desc",
                "NORI",
                now,
                now,
                "SYSTEM",
                "SYSTEM"
        );
    }

    private static TcModelMdf mdf(
            final long mdfKey,
            final long modelVersionKey,
            final String mdfName,
            final String xml
    ) {
        return new TcModelMdf(
                mdfKey,
                modelVersionKey,
                mdfName,
                xml.getBytes(StandardCharsets.UTF_8),
                OffsetDateTime.parse("2026-03-13T10:15:30+09:00")
        );
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
                OffsetDateTime.parse("2026-03-13T10:15:30+09:00")
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
                OffsetDateTime.parse("2026-03-13T10:15:30+09:00")
        );
    }

    /**
     * 테스트용 mock fixture입니다.
     */
    private static final class Fixture {

        private final TcModelStore modelStore = mock(TcModelStore.class);
        private final TcModelParamStore modelParamStore = mock(TcModelParamStore.class);
        private final TcModelSecsMessageStore modelSecsMessageStore = mock(TcModelSecsMessageStore.class);
        private final TcModelSocketMessageStore modelSocketMessageStore = mock(TcModelSocketMessageStore.class);
        private final TcModelMesMessageStore modelMesMessageStore = mock(TcModelMesMessageStore.class);
        private final TcModelVariableIdStore modelVariableIdStore = mock(TcModelVariableIdStore.class);
        private final TcModelReportIdStore modelReportIdStore = mock(TcModelReportIdStore.class);
        private final TcModelEventIdStore modelEventIdStore = mock(TcModelEventIdStore.class);
        private final TcModelMessageFlowStore modelMessageFlowStore = mock(TcModelMessageFlowStore.class);
        private final TcModelMdfStore modelMdfStore = mock(TcModelMdfStore.class);
        private final TcModelDcopItemStore modelDcopItemStore = mock(TcModelDcopItemStore.class);
        private final TcModelPlcGroupStore modelPlcGroupStore = mock(TcModelPlcGroupStore.class);
        private final TcModelPlcRegisterStore modelPlcRegisterStore = mock(TcModelPlcRegisterStore.class);
        private final JpaModelDetailCommandPort port = new JpaModelDetailCommandPort(
                modelStore,
                modelParamStore,
                modelSecsMessageStore,
                modelSocketMessageStore,
                modelMesMessageStore,
                modelVariableIdStore,
                modelReportIdStore,
                modelEventIdStore,
                modelMessageFlowStore,
                modelMdfStore,
                modelDcopItemStore,
                modelPlcGroupStore,
                modelPlcRegisterStore
        );
    }
}
