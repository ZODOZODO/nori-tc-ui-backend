package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.port.db.ModelCrudPort;
import com.nori.tc.ui.core.port.db.ModelFlowCommandPort;
import com.nori.tc.ui.domain.common.model.UiModelStatus;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.model.UiMessageFlow;
import com.nori.tc.ui.domain.model.UiModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ModelFlowCommandService} 단위 테스트.
 *
 * <p>SPEC line 131-132 의 Acceptance Criteria 매핑:
 * <ul>
 *   <li>POST/PUT 시 EDIT version 가드 — modelVersion = "EDIT" 만 허용.</li>
 *   <li>blank workflow_name / message_name / action_name 거부 — UiBadRequestException.</li>
 *   <li>존재하지 않는 model_version_key → UiNotFoundException.</li>
 *   <li>path flowId 와 body flowId 불일치 → UiBadRequestException.</li>
 * </ul></p>
 */
class ModelFlowCommandServiceTest {

    private static final long MODEL_VERSION_KEY = 2401L;
    private static final long FLOW_ID = 11L;

    private ModelCrudPort modelCrudPort;
    private ModelFlowCommandPort modelFlowCommandPort;
    private ModelFlowCommandService modelFlowCommandService;

    @BeforeEach
    void setUp() {
        modelCrudPort = mock(ModelCrudPort.class);
        modelFlowCommandPort = mock(ModelFlowCommandPort.class);
        modelFlowCommandService = new ModelFlowCommandService(modelCrudPort, modelFlowCommandPort);
    }

    @Test
    void createFlowDelegatesToPortAndReturnsWorkflowKey() {
        when(modelCrudPort.findByModelVersionKey(MODEL_VERSION_KEY)).thenReturn(Optional.of(editModel()));
        when(modelFlowCommandPort.insertFlow(MODEL_VERSION_KEY, "handleLotStart", "S6F11")).thenReturn(99L);

        final long workflowKey = modelFlowCommandService.createFlow(MODEL_VERSION_KEY, "handleLotStart", "S6F11");

        assertEquals(99L, workflowKey);
        verify(modelFlowCommandPort).insertFlow(MODEL_VERSION_KEY, "handleLotStart", "S6F11");
    }

    @Test
    void createFlowThrowsBadRequestWhenWorkflowNameBlank() {
        assertThrows(
                UiBadRequestException.class,
                () -> modelFlowCommandService.createFlow(MODEL_VERSION_KEY, "  ", "S6F11")
        );
        verify(modelFlowCommandPort, never()).insertFlow(anyLong(), anyString(), anyString());
    }

    @Test
    void createFlowThrowsBadRequestWhenMessageNameBlank() {
        assertThrows(
                UiBadRequestException.class,
                () -> modelFlowCommandService.createFlow(MODEL_VERSION_KEY, "handleLotStart", null)
        );
        verify(modelFlowCommandPort, never()).insertFlow(anyLong(), anyString(), anyString());
    }

    @Test
    void createFlowThrowsNotFoundWhenModelMissing() {
        when(modelCrudPort.findByModelVersionKey(MODEL_VERSION_KEY)).thenReturn(Optional.empty());

        assertThrows(
                UiNotFoundException.class,
                () -> modelFlowCommandService.createFlow(MODEL_VERSION_KEY, "handleLotStart", "S6F11")
        );
        verify(modelFlowCommandPort, never()).insertFlow(anyLong(), anyString(), anyString());
    }

    @Test
    void createFlowThrowsBadRequestWhenModelVersionIsNotEdit() {
        when(modelCrudPort.findByModelVersionKey(MODEL_VERSION_KEY))
                .thenReturn(Optional.of(modelWithVersion("v1")));

        assertThrows(
                UiBadRequestException.class,
                () -> modelFlowCommandService.createFlow(MODEL_VERSION_KEY, "handleLotStart", "S6F11")
        );
        verify(modelFlowCommandPort, never()).insertFlow(anyLong(), anyString(), anyString());
    }

    @Test
    void updateFlowDelegatesToPort() {
        when(modelCrudPort.findByModelVersionKey(MODEL_VERSION_KEY)).thenReturn(Optional.of(editModel()));
        final UiMessageFlow request = sampleFlow(FLOW_ID);

        modelFlowCommandService.updateFlow(MODEL_VERSION_KEY, FLOW_ID, request);

        verify(modelFlowCommandPort).updateFlow(eq(MODEL_VERSION_KEY), eq(FLOW_ID), eq(request));
    }

    @Test
    void updateFlowThrowsBadRequestWhenFlowIdMismatchBetweenPathAndBody() {
        when(modelCrudPort.findByModelVersionKey(MODEL_VERSION_KEY)).thenReturn(Optional.of(editModel()));
        final UiMessageFlow request = sampleFlow(FLOW_ID + 1);

        assertThrows(
                UiBadRequestException.class,
                () -> modelFlowCommandService.updateFlow(MODEL_VERSION_KEY, FLOW_ID, request)
        );
        verify(modelFlowCommandPort, never()).updateFlow(anyLong(), anyLong(), any());
    }

    @Test
    void updateFlowThrowsBadRequestWhenRequestNull() {
        assertThrows(
                UiBadRequestException.class,
                () -> modelFlowCommandService.updateFlow(MODEL_VERSION_KEY, FLOW_ID, null)
        );
        verify(modelFlowCommandPort, never()).updateFlow(anyLong(), anyLong(), any());
    }

    @Test
    void updateFlowThrowsBadRequestWhenActionNameBlank() {
        when(modelCrudPort.findByModelVersionKey(MODEL_VERSION_KEY)).thenReturn(Optional.of(editModel()));
        final UiMessageFlow request = new UiMessageFlow(
                FLOW_ID,
                "handleLotStart",
                "S6F11",
                "EVT_LOT_START",
                "TRX-001",
                null,
                "  ",
                null,
                List.of()
        );

        assertThrows(
                UiBadRequestException.class,
                () -> modelFlowCommandService.updateFlow(MODEL_VERSION_KEY, FLOW_ID, request)
        );
        verify(modelFlowCommandPort, never()).updateFlow(anyLong(), anyLong(), any());
    }

    @Test
    void updateFlowThrowsNotFoundWhenModelMissing() {
        when(modelCrudPort.findByModelVersionKey(MODEL_VERSION_KEY)).thenReturn(Optional.empty());

        assertThrows(
                UiNotFoundException.class,
                () -> modelFlowCommandService.updateFlow(MODEL_VERSION_KEY, FLOW_ID, sampleFlow(FLOW_ID))
        );
        verify(modelFlowCommandPort, never()).updateFlow(anyLong(), anyLong(), any());
    }

    @Test
    void updateFlowThrowsBadRequestWhenModelVersionIsNotEdit() {
        when(modelCrudPort.findByModelVersionKey(MODEL_VERSION_KEY))
                .thenReturn(Optional.of(modelWithVersion("v1")));

        assertThrows(
                UiBadRequestException.class,
                () -> modelFlowCommandService.updateFlow(MODEL_VERSION_KEY, FLOW_ID, sampleFlow(FLOW_ID))
        );
        verify(modelFlowCommandPort, never()).updateFlow(anyLong(), anyLong(), any());
    }

    private static UiModel editModel() {
        return modelWithVersion("EDIT");
    }

    private static UiModel modelWithVersion(final String modelVersion) {
        return new UiModel(
                MODEL_VERSION_KEY,
                1001L,
                "model-A",
                null,
                modelVersion,
                UiProtocolType.SECS,
                UiModelStatus.DEVELOP,
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                "tester",
                "tester"
        );
    }

    private static UiMessageFlow sampleFlow(final long flowId) {
        return new UiMessageFlow(
                flowId,
                "handleLotStart",
                "S6F11",
                "EVT_LOT_START",
                "TRX-001",
                "{\"type\":\"AND\",\"children\":[]}",
                "DispatchToBusiness",
                "{\"mdfTemplateName\":\"M1\",\"fields\":[]}",
                List.of()
        );
    }
}
