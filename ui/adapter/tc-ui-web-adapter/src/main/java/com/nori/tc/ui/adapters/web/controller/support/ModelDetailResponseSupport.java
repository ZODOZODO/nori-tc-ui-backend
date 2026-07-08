package com.nori.tc.ui.adapters.web.controller.support;

import com.nori.tc.ui.adapters.web.dto.response.ModelDetailDataResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelDetailRowResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelDiffItemResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelDiffSectionResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelInfoResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelMdfContentResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelParentCommitResponse;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.port.db.ModelDetailQueryPort;
import com.nori.tc.ui.core.port.db.ModelParentCommitPort;
import com.nori.tc.ui.domain.model.UiModel;
import com.nori.tc.ui.domain.model.UiModelDcopItem;
import com.nori.tc.ui.domain.model.UiModelEventId;
import com.nori.tc.ui.domain.model.UiModelMdf;
import com.nori.tc.ui.domain.model.UiModelMesMessage;
import com.nori.tc.ui.domain.model.UiModelParam;
import com.nori.tc.ui.domain.model.UiModelPlcGroup;
import com.nori.tc.ui.domain.model.UiModelPlcRegister;
import com.nori.tc.ui.domain.model.UiModelReportId;
import com.nori.tc.ui.domain.model.UiModelSecsMessage;
import com.nori.tc.ui.domain.model.UiModelSocketMessage;
import com.nori.tc.ui.domain.model.UiModelVariableId;
import com.nori.tc.ui.domain.model.UiModelMessageFlow;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ModelController 응답 매핑과 상세 테이블 조립을 담당하는 보조 유틸리티입니다.
 */
public final class ModelDetailResponseSupport {

    private static final List<String> MODEL_PARAMETER_COLUMNS = List.of(
            "Parameter Name",
            "Parameter Value",
            "Description"
    );
    private static final List<String> SECS_MESSAGE_COLUMNS = List.of(
            "SECS Message Name",
            "Description"
    );
    private static final List<String> SOCKET_MESSAGE_COLUMNS = List.of(
            "Socket Message Name",
            "Description"
    );
    private static final List<String> MES_MESSAGE_COLUMNS = List.of(
            "MES Message Name",
            "Description"
    );
    private static final List<String> VARIABLE_ID_COLUMNS = List.of(
            "VariableId",
            "VID Type",
            "Description"
    );
    private static final List<String> REPORT_ID_COLUMNS = List.of(
            "ReportId",
            "VariableIdes",
            "Enabled",
            "Description"
    );
    private static final List<String> EVENT_ID_COLUMNS = List.of(
            "EventId",
            "ReportIdes",
            "Enabled",
            "Description"
    );
    private static final List<String> SECS_WORKFLOW_COLUMNS = List.of(
            "Workflow Name",
            "Message Name",
            "EventId",
            "TransactionId",
            "Filter",
            "Action Name",
            "Data Index",
            "Collect"
    );
    private static final List<String> SOCKET_WORKFLOW_COLUMNS = List.of(
            "Workflow Name",
            "Message Name",
            "Filter",
            "Action Name",
            "Data Index",
            "Collect"
    );
    private static final List<String> SECS_DCOP_ITEM_COLUMNS = List.of(
            "Dcop Item Name",
            "EventId",
            "VariableId",
            "Collection Rule",
            "Calculation Rule",
            "Order Rule"
    );
    private static final List<String> SOCKET_DCOP_ITEM_COLUMNS = List.of(
            "Dcop Item Name",
            "VariableId",
            "Collection Rule",
            "Calculation Rule",
            "Order Rule"
    );
    private static final List<String> PLC_GROUP_COLUMNS = List.of(
            "Message Name",
            "Area",
            "Start Address",
            "Read Count",
            "Poll Period (ms)",
            "Address Qualifier",
            "Description"
    );
    private static final List<String> PLC_REGISTER_COLUMNS = List.of(
            "Group",
            "Word Offset",
            "Variable Name",
            "Data Type",
            "Length",
            "Bit Index",
            "Byte Index",
            "Byte Order",
            "Word Order",
            "Scale",
            "BCD Signed",
            "Char Encoding",
            "Description"
    );

    private ModelDetailResponseSupport() {
    }

    /**
     * detailNode에 대응하는 상세 조회 응답을 생성합니다.
     */
    public static ModelDetailDataResponse buildDetailResponse(
            final ModelDetailQueryPort modelDetailQueryPort,
            final UiModel model,
            final long modelVersionKey,
            final String detailNode
    ) {
        final String normalizedNode = ModelControllerRequestSupport.normalizeDetailNode(detailNode);
        return switch (normalizedNode) {
            case "model-parameter" -> new ModelDetailDataResponse(
                    MODEL_PARAMETER_COLUMNS,
                    modelDetailQueryPort.findParamsByModelVersionKey(modelVersionKey).stream()
                            .map(ModelDetailResponseSupport::toModelParamRow)
                            .toList(),
                    List.of()
            );
            case "secs-message" -> new ModelDetailDataResponse(
                    SECS_MESSAGE_COLUMNS,
                    modelDetailQueryPort.findSecsMessagesByModelVersionKey(modelVersionKey).stream()
                            .map(ModelDetailResponseSupport::toSecsMessageRow)
                            .toList(),
                    List.of()
            );
            case "socket-message" -> new ModelDetailDataResponse(
                    SOCKET_MESSAGE_COLUMNS,
                    modelDetailQueryPort.findSocketMessagesByModelVersionKey(modelVersionKey).stream()
                            .map(ModelDetailResponseSupport::toSocketMessageRow)
                            .toList(),
                    List.of()
            );
            case "mes-message" -> new ModelDetailDataResponse(
                    MES_MESSAGE_COLUMNS,
                    modelDetailQueryPort.findMesMessagesByModelVersionKey(modelVersionKey).stream()
                            .map(ModelDetailResponseSupport::toMesMessageRow)
                            .toList(),
                    List.of()
            );
            case "variableides" -> new ModelDetailDataResponse(
                    VARIABLE_ID_COLUMNS,
                    modelDetailQueryPort.findVariableIdsByModelVersionKey(modelVersionKey).stream()
                            .map(ModelDetailResponseSupport::toVariableIdRow)
                            .toList(),
                    List.of()
            );
            case "reportides" -> new ModelDetailDataResponse(
                    REPORT_ID_COLUMNS,
                    modelDetailQueryPort.findReportIdsByModelVersionKey(modelVersionKey).stream()
                            .map(ModelDetailResponseSupport::toReportIdRow)
                            .toList(),
                    List.of()
            );
            case "eventides" -> new ModelDetailDataResponse(
                    EVENT_ID_COLUMNS,
                    modelDetailQueryPort.findEventIdsByModelVersionKey(modelVersionKey).stream()
                            .map(ModelDetailResponseSupport::toEventIdRow)
                            .toList(),
                    List.of()
            );
            case "workflow" -> {
                final boolean socketModel = ModelControllerRequestSupport.isSocketModel(model);
                final List<String> workflowColumns = socketModel ? SOCKET_WORKFLOW_COLUMNS : SECS_WORKFLOW_COLUMNS;
                final Map<Long, List<String>> flowDcopItemNames =
                        modelDetailQueryPort.findFlowDcopItemNamesByModelVersionKey(modelVersionKey);
                yield new ModelDetailDataResponse(
                        workflowColumns,
                        modelDetailQueryPort.findMessageFlowsByModelVersionKey(modelVersionKey).stream()
                                .map(messageFlow -> toWorkflowRow(
                                        messageFlow,
                                        socketModel,
                                        flowDcopItemNames.getOrDefault(messageFlow.messageFlowKey(), List.of())))
                                .toList(),
                        List.of()
                );
            }
            case "mdf" -> new ModelDetailDataResponse(
                    List.of(),
                    List.of(),
                    modelDetailQueryPort.findMdfByModelVersionKey(modelVersionKey)
                            .map(ModelDetailResponseSupport::toMdfContent)
                            .stream()
                            .toList()
            );
            case "dcop-itemes" -> {
                final boolean socketDcopModel = ModelControllerRequestSupport.isSocketModel(model);
                final List<String> dcopColumns = socketDcopModel
                        ? SOCKET_DCOP_ITEM_COLUMNS
                        : SECS_DCOP_ITEM_COLUMNS;
                yield new ModelDetailDataResponse(
                        dcopColumns,
                        modelDetailQueryPort.findDcopItemsByModelVersionKey(modelVersionKey).stream()
                                .map(dcopItem -> toDcopItemRow(dcopItem, socketDcopModel))
                                .toList(),
                        List.of()
                );
            }
            case "plc-group" -> new ModelDetailDataResponse(
                    PLC_GROUP_COLUMNS,
                    modelDetailQueryPort.findPlcGroupsByModelVersionKey(modelVersionKey).stream()
                            .map(ModelDetailResponseSupport::toPlcGroupRow)
                            .toList(),
                    List.of()
            );
            case "plc-register" -> new ModelDetailDataResponse(
                    PLC_REGISTER_COLUMNS,
                    modelDetailQueryPort.findPlcRegistersByModelVersionKey(modelVersionKey).stream()
                            .map(ModelDetailResponseSupport::toPlcRegisterRow)
                            .toList(),
                    List.of()
            );
            default -> null;
        };
    }

    /**
     * 도메인 페이지 응답을 API 응답 DTO 페이지로 변환합니다.
     */
    public static PagedResponse<ModelInfoResponse> toModelPage(final PagedResponse<UiModel> page) {
        final List<ModelInfoResponse> items = page.items().stream()
                .map(ModelDetailResponseSupport::toModelInfoResponse)
                .toList();
        return PagedResponse.of(items, page.offset(), page.limit(), page.count());
    }

    /**
     * 도메인 모델을 응답 DTO로 변환합니다.
     */
    public static ModelInfoResponse toModelInfoResponse(final UiModel model) {
        return new ModelInfoResponse(
                model.modelVersionKey(),
                model.modelKey(),
                model.modelName(),
                model.parentModel(),
                model.modelVersion(),
                model.commInterface(),
                model.status(),
                model.description(),
                model.maker(),
                model.createdAt(),
                model.updatedAt(),
                model.createdBy(),
                model.updatedBy()
        );
    }

    /**
     * core parent commit 결과를 API 응답 DTO로 변환합니다.
     */
    public static ModelParentCommitResponse toCommitParentResponse(
            final ModelParentCommitPort.CommitParentResult result
    ) {
        return new ModelParentCommitResponse(
                result.committed(),
                result.branchModelKey(),
                result.parentModelKey(),
                result.branchModelName(),
                result.parentModelName(),
                result.branchLatestVersion(),
                result.parentLatestVersion(),
                result.newParentVersion(),
                result.committedParentModelVersionKey(),
                result.sections().stream()
                        .map(ModelDetailResponseSupport::toDiffSectionResponse)
                        .toList()
        );
    }

    private static ModelDiffSectionResponse toDiffSectionResponse(
            final ModelParentCommitPort.DiffSection section
    ) {
        return new ModelDiffSectionResponse(
                section.detailNode(),
                section.columns(),
                section.added().stream().map(ModelDetailResponseSupport::toDiffItemResponse).toList(),
                section.changed().stream().map(ModelDetailResponseSupport::toDiffItemResponse).toList(),
                section.deleted().stream().map(ModelDetailResponseSupport::toDiffItemResponse).toList()
        );
    }

    private static ModelDiffItemResponse toDiffItemResponse(
            final ModelParentCommitPort.DiffItem item
    ) {
        return new ModelDiffItemResponse(
                item.identity(),
                item.branchValues(),
                item.parentValues()
        );
    }

    private static ModelDetailRowResponse toModelParamRow(final UiModelParam param) {
        return new ModelDetailRowResponse(modelRowId("param", param.modelParamKey()), List.of(
                nullToEmpty(param.paramName()),
                nullToEmpty(param.paramValue()),
                nullToEmpty(param.description())
        ));
    }

    private static ModelDetailRowResponse toSecsMessageRow(final UiModelSecsMessage message) {
        return new ModelDetailRowResponse(modelRowId("secs", message.secsMsgKey()), List.of(
                nullToEmpty(message.secsMsgName()),
                nullToEmpty(message.description())
        ));
    }

    private static ModelDetailRowResponse toMesMessageRow(final UiModelMesMessage message) {
        return new ModelDetailRowResponse(modelRowId("mes", message.mesMsgKey()), List.of(
                nullToEmpty(message.mesMsgName()),
                nullToEmpty(message.description())
        ));
    }

    private static ModelDetailRowResponse toSocketMessageRow(final UiModelSocketMessage message) {
        return new ModelDetailRowResponse(modelRowId("socket", message.socketMsgKey()), List.of(
                nullToEmpty(message.socketMsgName()),
                nullToEmpty(message.description())
        ));
    }

    private static ModelDetailRowResponse toVariableIdRow(final UiModelVariableId variableId) {
        final String variableIdType = variableId.variableIdType() == null ? "" : variableId.variableIdType().name();
        return new ModelDetailRowResponse(modelRowId("variable", variableId.variableKey()), List.of(
                nullToEmpty(variableId.variableId()),
                variableIdType,
                nullToEmpty(variableId.description())
        ));
    }

    private static ModelDetailRowResponse toReportIdRow(final UiModelReportId reportId) {
        return new ModelDetailRowResponse(modelRowId("report", reportId.reportKey()), List.of(
                nullToEmpty(reportId.reportId()),
                nullToEmpty(reportId.variableId()),
                Boolean.toString(reportId.enabled()),
                nullToEmpty(reportId.description())
        ));
    }

    private static ModelDetailRowResponse toEventIdRow(final UiModelEventId eventId) {
        return new ModelDetailRowResponse(modelRowId("event", eventId.eventKey()), List.of(
                nullToEmpty(eventId.eventId()),
                nullToEmpty(eventId.reportId()),
                Boolean.toString(eventId.enabled()),
                nullToEmpty(eventId.description())
        ));
    }

    private static ModelDetailRowResponse toWorkflowRow(
            final UiModelMessageFlow messageFlow,
            final boolean excludeEventFields,
            final List<String> collectDcopItemNames
    ) {
        // Collect 컬럼 = 이 flow 에 바인딩된 dcop item 이름들. diff 안정성을 위해 조회 계층에서 정렬된 상태로 온다.
        final String collect = String.join(", ", collectDcopItemNames);
        final List<String> values = new ArrayList<>();
        values.add(nullToEmpty(messageFlow.workflowName()));
        values.add(nullToEmpty(messageFlow.messageName()));
        if (!excludeEventFields) {
            values.add(nullToEmpty(messageFlow.eventId()));
            values.add(nullToEmpty(messageFlow.transactionId()));
        }
        values.add(nullToEmpty(messageFlow.workflowFilter()));
        values.add(nullToEmpty(messageFlow.actionName()));
        values.add(nullToEmpty(messageFlow.actionDataIndex()));
        values.add(collect);

        // buildWorkflowPreviewValues 는 SECS 기준 7개를 반환 — Collect 를 덧붙여 SECS 에서 values 와 길이를 맞춰야
        // (8=8) FE 가 preview(요약된 Filter/Data Index)를 채택한다. 길이가 어긋나면 raw 값으로 폴백된다.
        final List<String> previewValues = new ArrayList<>(
                ModelDetailPreviewSupport.buildWorkflowPreviewValues(messageFlow));
        previewValues.add(collect);
        return new ModelDetailRowResponse(
                modelRowId("workflow", messageFlow.messageFlowKey()),
                List.copyOf(values),
                List.copyOf(previewValues)
        );
    }

    private static ModelDetailRowResponse toDcopItemRow(final UiModelDcopItem dcopItem, final boolean excludeEventId) {
        final String collectionRule = dcopItem.collectionRule() == null ? "" : dcopItem.collectionRule().name();
        final String calculationRule = dcopItem.calculationRule() == null ? "" : dcopItem.calculationRule();
        final String orderRule = dcopItem.orderRule() == null ? "" : dcopItem.orderRule().toString();
        final List<String> values;
        if (excludeEventId) {
            values = List.of(
                    nullToEmpty(dcopItem.dcopItemName()),
                    nullToEmpty(dcopItem.variableId()),
                    collectionRule,
                    calculationRule,
                    orderRule
            );
        } else {
            values = List.of(
                    nullToEmpty(dcopItem.dcopItemName()),
                    nullToEmpty(dcopItem.eventId()),
                    nullToEmpty(dcopItem.variableId()),
                    collectionRule,
                    calculationRule,
                    orderRule
            );
        }
        return new ModelDetailRowResponse(modelRowId("dcop", dcopItem.dcopItemKey()), values);
    }

    private static ModelDetailRowResponse toPlcGroupRow(final UiModelPlcGroup group) {
        return new ModelDetailRowResponse(modelRowId("plcgroup", group.groupKey()), List.of(
                nullToEmpty(group.messageName()),
                nullToEmpty(group.area()),
                Integer.toString(group.startAddress()),
                Integer.toString(group.readCount()),
                Integer.toString(group.pollPeriodMs()),
                nullToEmpty(group.addressQualifier()),
                nullToEmpty(group.description())
        ));
    }

    private static ModelDetailRowResponse toPlcRegisterRow(final UiModelPlcRegister register) {
        return new ModelDetailRowResponse(modelRowId("plcregister", register.registerKey()), List.of(
                nullToEmpty(register.groupMessageName()),
                Integer.toString(register.wordOffset()),
                nullToEmpty(register.variableName()),
                nullToEmpty(register.dataType()),
                numberToEmpty(register.length()),
                numberToEmpty(register.bitIndex()),
                numberToEmpty(register.byteIndex()),
                nullToEmpty(register.byteOrder()),
                nullToEmpty(register.wordOrder()),
                numberToEmpty(register.scale()),
                booleanToEmpty(register.bcdSigned()),
                nullToEmpty(register.charEncoding()),
                nullToEmpty(register.description())
        ));
    }

    public static ModelMdfContentResponse toMdfContent(final UiModelMdf mdf) {
        final byte[] mdfFile = (mdf.mdfFile() == null) ? new byte[0] : mdf.mdfFile();
        final String xml = new String(mdfFile, StandardCharsets.UTF_8);
        return new ModelMdfContentResponse(nullToEmpty(mdf.mdfName()), xml);
    }

    private static String modelRowId(final String prefix, final Long key) {
        if (key == null || key <= 0L) {
            return "";
        }
        return prefix + ":" + key;
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }

    private static String numberToEmpty(final Number value) {
        return value == null ? "" : value.toString();
    }

    private static String booleanToEmpty(final Boolean value) {
        return value == null ? "" : value.toString();
    }
}
