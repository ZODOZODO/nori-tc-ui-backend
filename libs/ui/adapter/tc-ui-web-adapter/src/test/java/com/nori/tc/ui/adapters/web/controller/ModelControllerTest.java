package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.db.domain.common.model.ModelStatus;
import com.nori.tc.db.domain.common.model.ProtocolType;
import com.nori.tc.db.domain.model.TcModel;
import com.nori.tc.db.domain.model.TcModelMdf;
import com.nori.tc.db.domain.model.TcModelWorkflow;
import com.nori.tc.ui.adapters.web.dto.request.ModelDetailSaveRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelDetailDataResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelMdfContentResponse;
import com.nori.tc.ui.core.port.db.ModelBranchCommandPort;
import com.nori.tc.ui.core.port.db.ModelCrudPort;
import com.nori.tc.ui.core.port.db.ModelDetailCommandPort;
import com.nori.tc.ui.core.port.db.ModelDetailQueryPort;
import com.nori.tc.ui.core.port.db.ModelParentCommitPort;
import com.nori.tc.ui.core.port.db.ModelRootCommandPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ModelController}의 model detail preview 및 MDF 업로드 계약을 검증합니다.
 */
class ModelControllerTest {

    @Test
    @DisplayName("workflow 상세 조회는 filter/data index preview 값을 함께 반환합니다")
    void getDetailReturnsWorkflowPreviewValues() {
        final Fixture fixture = new Fixture();
        final TcModel model = model(101L);
        final TcModelWorkflow workflow = new TcModelWorkflow(
                77L,
                101L,
                "WF_READY",
                "READY_MSG",
                "1001",
                "T01",
                """
                        {
                          "and": [
                            {
                              "from": "data",
                              "path": "status",
                              "comparison": "equals",
                              "expected": "ok",
                              "transforms": ["trim", "lower"]
                            },
                            {
                              "from": "metadata",
                              "path": "eventType",
                              "comparison": "equals",
                              "expected": "READY_MSG"
                            }
                          ]
                        }
                        """,
                "PUBLISH_MES_COMMAND",
                """
                        {
                          "mdfTemplateName": "TOOL_CONDITION_REPLY_MES",
                          "fields": {
                            "EQPID": { "from": "data", "path": "eqpId", "transforms": ["trim"] }
                          }
                        }
                        """,
                OffsetDateTime.parse("2026-03-13T10:15:30+09:00")
        );

        when(fixture.modelCrudPort.findByModelVersionKey(101L)).thenReturn(Optional.of(model));
        when(fixture.modelDetailQueryPort.findWorkflowsByModelVersionKey(101L)).thenReturn(List.of(workflow));

        final ResponseEntity<ApiResponse<ModelDetailDataResponse>> response = fixture.controller.getDetail(101L, "workflow");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        final ApiResponse<ModelDetailDataResponse> responseBody = response.getBody();
        assertNotNull(responseBody);
        final ModelDetailDataResponse body = responseBody.data();
        assertEquals(1, body.rows().size());
        assertEquals(
                "and(data.status {comparison=equals, expected=\"ok\", transforms=[trim, lower]}, "
                        + "metadata.eventType {comparison=equals, expected=\"READY_MSG\"})",
                body.rows().get(0).previewValues().get(4)
        );
        assertEquals(
                "mdfTemplateName=TOOL_CONDITION_REPLY_MES / EQPID {from=data, path=eqpId, transforms=[trim]}",
                body.rows().get(0).previewValues().get(6)
        );
        assertEquals(workflow.workflowFilter(), body.rows().get(0).values().get(4));
    }

    @Test
    @DisplayName("workflow 상세 저장은 잘못된 workflow_filter를 400으로 거절합니다")
    void saveDetailRowsRejectsInvalidWorkflowFilter() {
        final Fixture fixture = new Fixture();
        when(fixture.modelCrudPort.findByModelVersionKey(101L)).thenReturn(Optional.of(model(101L)));

        final ModelDetailSaveRequest request = workflowSaveRequest(
                """
                        {
                          "from": "data",
                          "path": "status",
                          "comparison": "equals",
                          "expected": "READY"
                        }
                        """,
                """
                        {
                          "mdfTemplateName": "TOOL_CONDITION_REPLY_MES",
                          "fields": {
                            "EQPID": "eqpId"
                          }
                        }
                        """
        );

        final ResponseEntity<ApiResponse<ModelDetailDataResponse>> response =
                fixture.controller.saveDetailRows(101L, "workflow", request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("INVALID_REQUEST", response.getBody().errorCode());
        assertEquals(
                "workflow 1행의 workflow_filter가 올바르지 않습니다. workflow_filter 루트는 and 또는 or 그룹이어야 합니다.",
                response.getBody().errorMsg()
        );
        verify(fixture.modelDetailCommandPort, never()).saveDetailRows(anyLong(), any(String.class), any());
    }

    @Test
    @DisplayName("workflow 상세 저장은 잘못된 action_data_index를 400으로 거절합니다")
    void saveDetailRowsRejectsInvalidActionDataIndex() {
        final Fixture fixture = new Fixture();
        when(fixture.modelCrudPort.findByModelVersionKey(101L)).thenReturn(Optional.of(model(101L)));

        final ModelDetailSaveRequest request = workflowSaveRequest(
                """
                        {
                          "and": [
                            {
                              "from": "data",
                              "path": "status",
                              "comparison": "equals",
                              "expected": "READY"
                            }
                          ]
                        }
                        """,
                """
                        {
                          "fields": {
                            "EQPID": "eqpId"
                          }
                        }
                        """
        );

        final ResponseEntity<ApiResponse<ModelDetailDataResponse>> response =
                fixture.controller.saveDetailRows(101L, "workflow", request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("INVALID_REQUEST", response.getBody().errorCode());
        assertEquals(
                "workflow 1행의 action_data_index가 올바르지 않습니다. action_data_index의 mdfTemplateName은 필수입니다.",
                response.getBody().errorMsg()
        );
        verify(fixture.modelDetailCommandPort, never()).saveDetailRows(anyLong(), any(String.class), any());
    }

    @Test
    @DisplayName("MDF 업로드는 기존 이름을 유지해 저장하고 저장 결과를 반환합니다")
    void uploadMdfSavesValidatedXml() {
        final Fixture fixture = new Fixture();
        final TcModel model = model(101L);
        final TcModelMdf existing = mdf(88L, 101L, "EXISTING_MDF", "<mdf><old/></mdf>");
        final String xml = "<mdf><message name=\"REQ\">OK</message></mdf>";
        final MockMultipartFile file = new MockMultipartFile(
                "file",
                "updated-mdf.xml",
                "application/xml",
                xml.getBytes(StandardCharsets.UTF_8)
        );

        when(fixture.modelCrudPort.findByModelVersionKey(101L)).thenReturn(Optional.of(model));
        when(fixture.modelDetailQueryPort.findMdfByModelVersionKey(101L)).thenReturn(Optional.of(existing));
        when(fixture.modelDetailCommandPort.saveMdf(anyLong(), any(String.class), any(byte[].class)))
                .thenReturn(mdf(88L, 101L, "EXISTING_MDF", xml));

        final ResponseEntity<ApiResponse<ModelMdfContentResponse>> response =
                fixture.controller.uploadMdf(101L, file, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        final ApiResponse<ModelMdfContentResponse> responseBody = response.getBody();
        assertNotNull(responseBody);
        assertEquals("EXISTING_MDF", responseBody.data().name());
        assertEquals(xml, responseBody.data().xml());

        final ArgumentCaptor<byte[]> xmlCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(fixture.modelDetailCommandPort).saveMdf(org.mockito.ArgumentMatchers.eq(101L), org.mockito.ArgumentMatchers.eq("EXISTING_MDF"), xmlCaptor.capture());
        assertEquals(xml, new String(xmlCaptor.getValue(), StandardCharsets.UTF_8));
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

    /**
     * workflow 상세 저장 요청 본문을 생성합니다.
     */
    private static ModelDetailSaveRequest workflowSaveRequest(
            final String workflowFilter,
            final String actionDataIndex
    ) {
        return new ModelDetailSaveRequest(List.of(
                new ModelDetailSaveRequest.ModelDetailSaveRowItem(
                        "workflow-1",
                        List.of(
                                "WF_READY",
                                "READY_MSG",
                                "",
                                "",
                                workflowFilter,
                                "PUBLISH_MES_COMMAND",
                                actionDataIndex
                        )
                )
        ));
    }

    /**
     * 테스트용 mock fixture입니다.
     */
    private static final class Fixture {

        private final ModelCrudPort modelCrudPort = mock(ModelCrudPort.class);
        private final ModelDetailQueryPort modelDetailQueryPort = mock(ModelDetailQueryPort.class);
        private final ModelDetailCommandPort modelDetailCommandPort = mock(ModelDetailCommandPort.class);
        private final ModelRootCommandPort modelRootCommandPort = mock(ModelRootCommandPort.class);
        private final ModelBranchCommandPort modelBranchCommandPort = mock(ModelBranchCommandPort.class);
        private final ModelParentCommitPort modelParentCommitPort = mock(ModelParentCommitPort.class);
        private final ModelController controller = new ModelController(
                modelCrudPort,
                modelDetailQueryPort,
                modelDetailCommandPort,
                modelRootCommandPort,
                modelBranchCommandPort,
                modelParentCommitPort
        );
    }
}
