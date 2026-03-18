package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.model.upsert.UpsertTcModel;
import com.nori.tc.db.domain.model.TcModelDcopItem;
import com.nori.tc.db.domain.model.TcModelEventId;
import com.nori.tc.db.domain.model.TcModelMdf;
import com.nori.tc.db.domain.model.TcModel;
import com.nori.tc.db.domain.model.TcModelParam;
import com.nori.tc.db.domain.model.TcModelReportId;
import com.nori.tc.db.domain.model.TcModelMesMessage;
import com.nori.tc.db.domain.model.TcModelSecsMessage;
import com.nori.tc.db.domain.model.TcModelSocketMessage;
import com.nori.tc.db.domain.model.TcModelVariableId;
import com.nori.tc.db.domain.model.TcModelWorkflow;
import com.nori.tc.ui.adapters.web.controller.support.ModelDetailPreviewSupport;
import com.nori.tc.ui.adapters.web.controller.support.ModelDetailWorkflowJsonSupport;
import com.nori.tc.ui.adapters.web.controller.support.UiPageRequestSupport;
import com.nori.tc.ui.adapters.web.dto.request.ModelBranchCreateRequest;
import com.nori.tc.ui.adapters.web.dto.request.ModelCheckinRequest;
import com.nori.tc.ui.adapters.web.dto.request.ModelDetailSaveRequest;
import com.nori.tc.ui.adapters.web.dto.request.ModelInfoUpdateRequest;
import com.nori.tc.ui.adapters.web.dto.request.ModelParentCommitRequest;
import com.nori.tc.ui.adapters.web.dto.request.ModelRootCreateRequest;
import com.nori.tc.ui.adapters.web.dto.request.ModelUpsertRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelDetailDataResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelDetailRowResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelDeleteBatchResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelDiffItemResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelDiffSectionResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelInfoResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelMdfContentResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelParentCommitResponse;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.port.db.ModelDetailCommandPort;
import com.nori.tc.ui.core.port.db.ModelBranchCommandPort;
import com.nori.tc.ui.core.port.db.ModelCrudPort;
import com.nori.tc.ui.core.port.db.ModelDetailQueryPort;
import com.nori.tc.ui.core.port.db.ModelParentCommitPort;
import com.nori.tc.ui.core.port.db.ModelRootCommandPort;
import com.nori.tc.ui.domain.auth.UserPrincipal;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * 모델 정보(Model Info) 관리 REST API 컨트롤러입니다.
 *
 * <p>제공 엔드포인트:</p>
 * <ul>
 *   <li>GET /api/model</li>
 *   <li>GET /api/model/{modelVersionKey}</li>
 *   <li>POST /api/model/roots</li>
 *   <li>PUT /api/model/{modelKey}/info</li>
 *   <li>POST /api/model/{modelKey}/branches</li>
 *   <li>POST /api/model/{modelKey}/commit-parent</li>
 *   <li>DELETE /api/model/{modelKey}/branches/deprecated</li>
 *   <li>POST /api/model</li>
 *   <li>PUT /api/model/{modelVersionKey}</li>
 *   <li>DELETE /api/model/{modelVersionKey} (legacy version delete)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/model")
public class ModelController {

    private static final Logger log = LoggerFactory.getLogger(ModelController.class);
    private static final String EDIT_MODEL_VERSION = "EDIT";
    private static final List<String> MODEL_PARAMETER_COLUMNS = List.of(
            "Parameter Name",
            "Parameter Value",
            "Description"
    );
    private static final List<String> SECS_MESSAGE_COLUMNS = List.of(
            "SECS Message Name",
            "Description",
            "Data Indexes"
    );
    private static final List<String> SOCKET_MESSAGE_COLUMNS = List.of(
            "Socket Message Name",
            "Description",
            "Data Indexes"
    );
    private static final List<String> MES_MESSAGE_COLUMNS = List.of(
            "MES Message Name",
            "Description",
            "Data Indexes"
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
    // SECS 모델용 workflow 컬럼: EventId, TransactionId 포함 7개
    private static final List<String> SECS_WORKFLOW_COLUMNS = List.of(
            "Workflow Name",
            "Message Name",
            "EventId",
            "TransactionId",
            "Filter",
            "Action Name",
            "Data Index"
    );
    // SOCKET 모델용 workflow 컬럼: EventId, TransactionId 미사용이므로 제외
    private static final List<String> SOCKET_WORKFLOW_COLUMNS = List.of(
            "Workflow Name",
            "Message Name",
            "Filter",
            "Action Name",
            "Data Index"
    );
    // SECS 모델용 dcop item 컬럼 (7개): EventId 포함
    private static final List<String> SECS_DCOP_ITEM_COLUMNS = List.of(
            "Dcop Item Name",     // [0]
            "Workflow Name",      // [1]
            "EventId",            // [2]
            "VariableId",         // [3]
            "Collection Rule",    // [4]
            "Calculation Rule",   // [5]
            "Order Rule"          // [6]
    );
    // SOCKET 모델용 dcop item 컬럼 (6개): EventId 미사용이므로 제외
    private static final List<String> SOCKET_DCOP_ITEM_COLUMNS = List.of(
            "Dcop Item Name",     // [0]
            "Workflow Name",      // [1]
            "VariableId",         // [2]
            "Collection Rule",    // [3]
            "Calculation Rule",   // [4]
            "Order Rule"          // [5]
    );
    private static final String DEFAULT_MDF_NAME = "MDF";
    // SECS workflow 컬럼 기준 인덱스 (7개 컬럼)
    private static final int SECS_WORKFLOW_FILTER_VALUE_INDEX = 4;
    private static final int SECS_ACTION_DATA_INDEX_VALUE_INDEX = 6;
    private final ModelCrudPort modelCrudPort;
    private final ModelDetailQueryPort modelDetailQueryPort;
    private final ModelDetailCommandPort modelDetailCommandPort;
    private final ModelRootCommandPort modelRootCommandPort;
    private final ModelBranchCommandPort modelBranchCommandPort;
    private final ModelParentCommitPort modelParentCommitPort;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * @param modelCrudPort 모델 CRUD 포트
     * @param modelDetailQueryPort 모델 상세 조회 포트
     * @param modelDetailCommandPort 모델 상세 저장 포트
     * @param modelRootCommandPort root model 관리 포트
     * @param modelBranchCommandPort branch model 관리 포트
     * @param modelParentCommitPort parent commit 포트
     */
    public ModelController(
            final ModelCrudPort modelCrudPort,
            final ModelDetailQueryPort modelDetailQueryPort,
            final ModelDetailCommandPort modelDetailCommandPort,
            final ModelRootCommandPort modelRootCommandPort,
            final ModelBranchCommandPort modelBranchCommandPort,
            final ModelParentCommitPort modelParentCommitPort
    ) {
        this.modelCrudPort = Objects.requireNonNull(modelCrudPort, "modelCrudPort is null");
        this.modelDetailQueryPort = Objects.requireNonNull(modelDetailQueryPort, "modelDetailQueryPort is null");
        this.modelDetailCommandPort = Objects.requireNonNull(modelDetailCommandPort, "modelDetailCommandPort is null");
        this.modelRootCommandPort = Objects.requireNonNull(modelRootCommandPort, "modelRootCommandPort is null");
        this.modelBranchCommandPort = Objects.requireNonNull(modelBranchCommandPort, "modelBranchCommandPort is null");
        this.modelParentCommitPort = Objects.requireNonNull(modelParentCommitPort, "modelParentCommitPort is null");
    }

    /**
     * 모델 목록을 페이지 단위로 조회합니다.
     *
     * @param offset 조회 시작 위치(기본값 0)
     * @param limit  조회 건수(기본값 100, 최대 500)
     * @return 목록 페이지 응답
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<ModelInfoResponse>>> list(
            @RequestParam(name = "offset", required = false) final Integer offset,
            @RequestParam(name = "limit", required = false) final Integer limit
    ) {
        final PageRequest pageRequest = UiPageRequestSupport.resolve(offset, limit);

        if (log.isDebugEnabled()) {
            log.debug("모델 목록 조회 요청. offset={}, limit={}", pageRequest.offset(), pageRequest.limit());
        }

        final PagedResponse<TcModel> page = modelCrudPort.findAll(pageRequest);
        final PagedResponse<ModelInfoResponse> responsePage = toModelPage(page);

        if (log.isDebugEnabled()) {
            log.debug("모델 목록 조회 완료. offset={}, limit={}, pageSize={}, totalCount={}",
                    responsePage.offset(), responsePage.limit(), responsePage.items().size(), responsePage.count());
        }

        return ResponseEntity.ok(ApiResponse.success(responsePage));
    }

    /**
     * 모델 버전 키 기준으로 단건을 조회합니다.
     *
     * @param modelVersionKey 모델 버전 키
     * @return 단건 조회 응답
     */
    @GetMapping("/{modelVersionKey}")
    public ResponseEntity<ApiResponse<ModelInfoResponse>> get(
            @PathVariable final long modelVersionKey
    ) {
        if (log.isDebugEnabled()) {
            log.debug("모델 단건 조회 요청. modelVersionKey={}", modelVersionKey);
        }

        final Optional<TcModel> optionalModel = modelCrudPort.findByModelVersionKey(modelVersionKey);
        if (optionalModel.isEmpty()) {
            log.warn("모델 단건 조회 결과 없음. modelVersionKey={}", modelVersionKey);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "모델을 찾을 수 없습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(toModelInfoResponse(optionalModel.get())));
    }

    /**
     * Model 상세 노드 데이터를 조회합니다.
     *
     * <p>노드별 매핑:</p>
     * <ul>
     *   <li>model-parameter -> tc_model_param</li>
     *   <li>secs-message -> tc_model_secs_message</li>
     *   <li>socket-message -> tc_model_socket_message</li>
     *   <li>variableides -> tc_model_variableid</li>
     *   <li>reportides -> tc_model_reportid</li>
     *   <li>eventides -> tc_model_eventid</li>
     *   <li>workflow -> tc_model_workflow</li>
     *   <li>mdf -> tc_model_mdf</li>
     *   <li>dcop-itemes -> tc_model_dcop_item</li>
     * </ul>
     *
     * @param modelVersionKey 모델 버전 키
     * @param detailNode 상세 노드 식별자
     * @return 상세 노드 데이터 응답
     */
    @GetMapping("/{modelVersionKey}/details/{detailNode}")
    public ResponseEntity<ApiResponse<ModelDetailDataResponse>> getDetail(
            @PathVariable final long modelVersionKey,
            @PathVariable final String detailNode
    ) {
        if (log.isDebugEnabled()) {
            log.debug("모델 상세 노드 조회 요청. modelVersionKey={}, detailNode={}", modelVersionKey, detailNode);
        }

        final Optional<TcModel> optionalModelForDetail = modelCrudPort.findByModelVersionKey(modelVersionKey);
        if (optionalModelForDetail.isEmpty()) {
            log.warn("모델 상세 노드 조회 결과 없음 - 모델 미존재. modelVersionKey={}, detailNode={}", modelVersionKey, detailNode);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "모델을 찾을 수 없습니다."));
        }

        final ModelDetailDataResponse response = buildDetailResponse(optionalModelForDetail.get(), modelVersionKey, detailNode);

        if (response == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("INVALID_NODE", "지원하지 않는 상세 노드입니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 일반 상세 테이블 row를 현재 model version에 저장합니다.
     *
     * <p>MDF는 multipart 업로드 계약을 사용하므로 본 엔드포인트에서 제외합니다.</p>
     *
     * @param modelVersionKey 대상 모델 버전 키
     * @param detailNode 저장할 상세 노드 식별자
     * @param request row 저장 요청 본문
     * @return 저장 후 재조회한 상세 노드 응답
     */
    @PutMapping(
            path = "/{modelVersionKey}/details/{detailNode}",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse<ModelDetailDataResponse>> saveDetailRows(
            @PathVariable final long modelVersionKey,
            @PathVariable final String detailNode,
            @Valid @RequestBody final ModelDetailSaveRequest request
    ) {
        final Optional<TcModel> optionalModel = modelCrudPort.findByModelVersionKey(modelVersionKey);
        if (optionalModel.isEmpty()) {
            log.warn("모델 상세 저장 대상 없음. modelVersionKey={}, detailNode={}", modelVersionKey, detailNode);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "모델을 찾을 수 없습니다."));
        }
        if (!isEditVersion(optionalModel.get())) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("INVALID_MODEL_VERSION", "EDIT version만 저장할 수 있습니다."));
        }

        final String normalizedNode = normalizeDetailNode(detailNode);
        if ("mdf".equals(normalizedNode)) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("INVALID_NODE", "MDF는 upload API를 사용해 주세요."));
        }

        log.info("모델 상세 row 저장 요청. modelVersionKey={}, detailNode={}, rowCount={}",
                modelVersionKey, normalizedNode, request.rows() == null ? 0 : request.rows().size());

        final TcModel model = optionalModel.get();
        final boolean isSocketWorkflow = "workflow".equals(normalizedNode) && isSocketModel(model);
        final boolean isSocketDcopItem = "dcop-itemes".equals(normalizedNode) && isSocketModel(model);

        // SOCKET 모델의 workflow는 5개 컬럼(EventId/TransactionId 제외)으로 전송됩니다.
        // SOCKET 모델의 dcop-itemes는 6개 컬럼(EventId 제외)으로 전송됩니다.
        // 두 경우 모두 DB 저장 계층이 기대하는 SECS 기준 values 형식으로 확장합니다.
        final List<ModelDetailSaveRequest.ModelDetailSaveRowItem> normalizedRows;
        if (isSocketWorkflow) {
            normalizedRows = expandSocketWorkflowValues(request.rows());
        } else if (isSocketDcopItem) {
            normalizedRows = expandSocketDcopItemValues(request.rows());
        } else {
            normalizedRows = request.rows();
        }

        final String workflowValidationMessage = validateWorkflowJsonRows(normalizedNode, normalizedRows, isSocketWorkflow);
        if (workflowValidationMessage != null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("INVALID_REQUEST", workflowValidationMessage));
        }

        modelDetailCommandPort.saveDetailRows(
                modelVersionKey,
                normalizedNode,
                normalizedRows == null
                        ? List.of()
                        : normalizedRows.stream()
                                .map(row -> new ModelDetailCommandPort.DetailRowCommand(row.id(), row.values()))
                                .toList()
        );

        final ModelDetailDataResponse response = buildDetailResponse(model, modelVersionKey, normalizedNode);
        if (response == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("INVALID_NODE", "지원하지 않는 상세 노드입니다."));
        }
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * workflow 상세 저장 시 row 단위 JSON 계약을 사전 검증합니다.
     *
     * <p>preview와 저장이 서로 다른 규칙을 보지 않도록 UI 계층에서 동일한 canonical 계약을 사용합니다.</p>
     *
     * @param detailNode 저장 대상 상세 노드
     * @param rows 요청 row 목록
     * @return 검증 실패 메시지. 정상인 경우 null
     */
    /**
     * workflow 상세 저장 시 row 단위 JSON 계약을 사전 검증합니다.
     *
     * <p>SOCKET 모델의 경우 EventId, TransactionId 컬럼이 없으므로 filter, data index의 인덱스가 달라집니다.
     * isSocketWorkflow가 true이면 SOCKET 기준 인덱스를 사용합니다.</p>
     *
     * @param detailNode 저장 대상 상세 노드
     * @param rows 요청 row 목록 (이미 7개 values로 변환된 상태)
     * @param isSocketWorkflow SOCKET 모델의 workflow 여부 (변환 후에도 인덱스 참조용)
     * @return 검증 실패 메시지. 정상인 경우 null
     */
    private String validateWorkflowJsonRows(
            final String detailNode,
            final List<ModelDetailSaveRequest.ModelDetailSaveRowItem> rows,
            final boolean isSocketWorkflow
    ) {
        if (!"workflow".equals(detailNode) || rows == null || rows.isEmpty()) {
            return null;
        }

        // SOCKET workflow는 expandSocketWorkflowValues()로 7개 values로 변환된 상태이므로 SECS 인덱스 사용
        final int filterIndex = SECS_WORKFLOW_FILTER_VALUE_INDEX;
        final int dataIndexIndex = SECS_ACTION_DATA_INDEX_VALUE_INDEX;

        for (int index = 0; index < rows.size(); index++) {
            final ModelDetailSaveRequest.ModelDetailSaveRowItem row = rows.get(index);

            final String workflowFilter = valueAt(row.values(), filterIndex);
            try {
                ModelDetailWorkflowJsonSupport.validateWorkflowFilter(workflowFilter);
            } catch (IllegalArgumentException exception) {
                final String message = "workflow " + (index + 1) + "행의 workflow_filter가 올바르지 않습니다. "
                        + exception.getMessage();
                log.warn("workflow_filter 저장 검증 실패. detailNode={}, rowIndex={}, rowId={}, reason={}",
                        detailNode, index, row.id(), exception.getMessage());
                return message;
            }

            final String actionDataIndex = valueAt(row.values(), dataIndexIndex);
            try {
                ModelDetailWorkflowJsonSupport.validateActionDataIndex(actionDataIndex);
            } catch (IllegalArgumentException exception) {
                final String message = "workflow " + (index + 1) + "행의 action_data_index가 올바르지 않습니다. "
                        + exception.getMessage();
                log.warn("action_data_index 저장 검증 실패. detailNode={}, rowIndex={}, rowId={}, reason={}",
                        detailNode, index, row.id(), exception.getMessage());
                return message;
            }
        }
        return null;
    }

    /**
     * 모델이 SOCKET 통신 인터페이스를 사용하는지 확인합니다.
     *
     * @param model 확인 대상 모델
     * @return SOCKET 모델이면 true
     */
    private static boolean isSocketModel(final TcModel model) {
        return model != null
                && model.commInterface() != null
                && "SOCKET".equalsIgnoreCase(model.commInterface().name());
    }

    /**
     * SOCKET 모델의 workflow values(5개)를 DB 저장 계층이 기대하는 7개 형식으로 확장합니다.
     *
     * <p>SOCKET 컬럼 순서: [workflowName, messageName, filter, actionName, dataIndex]
     * DB 저장 계층 기대 순서: [workflowName, messageName, eventId, transactionId, filter, actionName, dataIndex]
     * EventId, TransactionId는 SOCKET 모델에서 사용하지 않으므로 빈 문자열로 채웁니다.</p>
     *
     * @param rows 5개 values를 가진 SOCKET workflow rows
     * @return 7개 values로 변환된 rows
     */
    private static List<ModelDetailSaveRequest.ModelDetailSaveRowItem> expandSocketWorkflowValues(
            final List<ModelDetailSaveRequest.ModelDetailSaveRowItem> rows
    ) {
        if (rows == null) {
            return null;
        }
        return rows.stream()
                .map(row -> {
                    final List<String> original = row.values() == null ? List.of() : row.values();
                    // [workflowName, messageName, "", "", filter, actionName, dataIndex]
                    final List<String> expanded = List.of(
                            getOrEmpty(original, 0),  // workflowName
                            getOrEmpty(original, 1),  // messageName
                            "",                       // eventId (SOCKET 미사용)
                            "",                       // transactionId (SOCKET 미사용)
                            getOrEmpty(original, 2),  // filter
                            getOrEmpty(original, 3),  // actionName
                            getOrEmpty(original, 4)   // dataIndex
                    );
                    return new ModelDetailSaveRequest.ModelDetailSaveRowItem(row.id(), expanded);
                })
                .toList();
    }

    /**
     * SOCKET 모델의 dcop item values(6개)를 DB 저장 계층이 기대하는 7개 형식으로 확장합니다.
     *
     * <p>SOCKET 컬럼 순서: [dcopItemName, workflowName, variableId, collectionRule, calculationRule, orderRule]
     * DB 저장 계층 기대 순서: [dcopItemName, workflowName, eventId, variableId, collectionRule, calculationRule, orderRule]
     * EventId는 SOCKET 모델에서 사용하지 않으므로 빈 문자열로 채웁니다.</p>
     *
     * @param rows 6개 values를 가진 SOCKET dcop item rows
     * @return 7개 values로 변환된 rows
     */
    private static List<ModelDetailSaveRequest.ModelDetailSaveRowItem> expandSocketDcopItemValues(
            final List<ModelDetailSaveRequest.ModelDetailSaveRowItem> rows
    ) {
        if (rows == null) {
            return null;
        }
        return rows.stream()
                .map(row -> {
                    final List<String> original = row.values() == null ? List.of() : row.values();
                    // [dcopItemName, workflowName, "", variableId, collectionRule, calculationRule, orderRule]
                    final List<String> expanded = List.of(
                            getOrEmpty(original, 0),  // dcopItemName
                            getOrEmpty(original, 1),  // workflowName
                            "",                       // eventId (SOCKET 미사용)
                            getOrEmpty(original, 2),  // variableId
                            getOrEmpty(original, 3),  // collectionRule
                            getOrEmpty(original, 4),  // calculationRule
                            getOrEmpty(original, 5)   // orderRule
                    );
                    return new ModelDetailSaveRequest.ModelDetailSaveRowItem(row.id(), expanded);
                })
                .toList();
    }

    /**
     * 리스트에서 지정한 인덱스의 값을 반환하고, 인덱스가 범위를 벗어나면 빈 문자열을 반환합니다.
     *
     * @param list 대상 리스트
     * @param index 인덱스
     * @return 해당 인덱스의 값 또는 빈 문자열
     */
    private static String getOrEmpty(final List<String> list, final int index) {
        if (list == null || index < 0 || index >= list.size()) {
            return "";
        }
        final String value = list.get(index);
        return value == null ? "" : value;
    }

    /**
     * MDF XML 파일을 업로드하여 현재 model version의 MDF를 교체 저장합니다.
     *
     * <p>업로드 파일은 UTF-8 XML 형식이어야 하며, XML 정합성 검증 실패 시 400을 반환합니다.</p>
     *
     * @param modelVersionKey 대상 모델 버전 키
     * @param file 업로드할 MDF 파일
     * @param mdfName 저장할 MDF 이름(선택)
     * @return 저장된 MDF 응답
     */
    @PutMapping(
            path = "/{modelVersionKey}/details/mdf",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<ApiResponse<ModelMdfContentResponse>> uploadMdf(
            @PathVariable final long modelVersionKey,
            @RequestParam("file") final MultipartFile file,
            @RequestParam(name = "mdfName", required = false) final String mdfName
    ) {
        final Optional<TcModel> optionalModel = modelCrudPort.findByModelVersionKey(modelVersionKey);
        if (optionalModel.isEmpty()) {
            log.warn("MDF 업로드 대상 모델 없음. modelVersionKey={}", modelVersionKey);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "모델을 찾을 수 없습니다."));
        }
        if (!isEditVersion(optionalModel.get())) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("INVALID_MODEL_VERSION", "EDIT version만 업로드할 수 있습니다."));
        }

        final String resolvedMdfName = resolveUploadMdfName(
                mdfName,
                file == null ? null : file.getOriginalFilename(),
                modelDetailQueryPort.findMdfByModelVersionKey(modelVersionKey).orElse(null)
        );
        final byte[] fileBytes = readUploadedBytes(file);

        log.info("MDF 업로드 요청. modelVersionKey={}, mdfName={}, fileName={}, fileSize={}",
                modelVersionKey,
                resolvedMdfName,
                file == null ? "" : nullToEmpty(file.getOriginalFilename()),
                fileBytes.length);

        final TcModelMdf saved = modelDetailCommandPort.saveMdf(modelVersionKey, resolvedMdfName, fileBytes);
        return ResponseEntity.ok(ApiResponse.success(toMdfContent(saved)));
    }

    /**
     * root model을 생성합니다.
     *
     * <p>신규 root는 항상 {@code parentModel=null, modelVersion=YY.MM.DD.0000, status=OPERATE} 정책으로 생성됩니다.</p>
     *
     * @param request root 생성 요청 본문
     * @param authentication 현재 로그인 사용자 정보
     * @return 생성된 root model의 최신 버전 정보
     */
    @PostMapping("/roots")
    public ResponseEntity<ApiResponse<ModelInfoResponse>> createRoot(
            @Valid @RequestBody final ModelRootCreateRequest request,
            final Authentication authentication
    ) {
        final String currentUser = resolveCurrentUser(authentication);
        log.info("root model 생성 요청. modelName={}, commInterface={}, currentUser={}",
                request.modelName(), request.commInterface(), currentUser);

        final TcModel created = modelRootCommandPort.createRootModel(new ModelRootCommandPort.CreateRootModelCommand(
                request.modelName(),
                request.commInterface(),
                request.maker(),
                currentUser
        ));

        log.info("root model 생성 완료. modelKey={}, modelName={}", created.modelKey(), created.modelName());
        return ResponseEntity.ok(ApiResponse.success(toModelInfoResponse(created)));
    }

    /**
     * root model의 공통 정보를 수정합니다.
     *
     * <p>현재 정책상 modelName은 불변이며 maker만 수정할 수 있습니다.</p>
     *
     * @param modelKey 수정 대상 model_key
     * @param request 수정 요청 본문
     * @param authentication 현재 로그인 사용자 정보
     * @return 수정된 root model 최신 버전 정보
     */
    @PutMapping("/{modelKey}/info")
    public ResponseEntity<ApiResponse<ModelInfoResponse>> updateModelInfo(
            @PathVariable final long modelKey,
            @Valid @RequestBody final ModelInfoUpdateRequest request,
            final Authentication authentication
    ) {
        final String currentUser = resolveCurrentUser(authentication);
        log.info("root model 정보 수정 요청. modelKey={}, currentUser={}", modelKey, currentUser);

        final TcModel updated = modelRootCommandPort.updateRootModelInfo(
                new ModelRootCommandPort.UpdateRootModelInfoCommand(modelKey, request.maker(), currentUser)
        );

        log.info("root model 정보 수정 완료. modelKey={}, modelName={}", updated.modelKey(), updated.modelName());
        return ResponseEntity.ok(ApiResponse.success(toModelInfoResponse(updated)));
    }

    /**
     * root model에서 branch model을 생성합니다.
     *
     * @param modelKey 부모 root model의 model_key
     * @param request branch 생성 요청 본문
     * @param authentication 현재 로그인 사용자 정보
     * @return 생성된 branch 최신 버전 정보
     */
    @PostMapping("/{modelKey}/branches")
    public ResponseEntity<ApiResponse<ModelInfoResponse>> createBranch(
            @PathVariable final long modelKey,
            @Valid @RequestBody final ModelBranchCreateRequest request,
            final Authentication authentication
    ) {
        final String currentUser = resolveCurrentUser(authentication);
        log.info("branch model 생성 요청. parentModelKey={}, sourceModelVersionKey={}, suffix={}, currentUser={}",
                modelKey, request.sourceModelVersionKey(), request.suffix(), currentUser);

        final TcModel created = modelBranchCommandPort.createBranchModel(
                new ModelBranchCommandPort.CreateBranchModelCommand(
                        modelKey,
                        request.suffix(),
                        request.sourceModelVersionKey(),
                        currentUser
                )
        );

        log.info("branch model 생성 완료. branchModelKey={}, branchModelName={}", created.modelKey(), created.modelName());
        return ResponseEntity.ok(ApiResponse.success(toModelInfoResponse(created)));
    }

    /**
     * 선택한 branch version을 EDIT version으로 checkout합니다.
     *
     * @param modelVersionKey 복제 기준 version key
     * @param authentication 현재 로그인 사용자 정보
     * @return 편집용 EDIT 모델 버전 정보
     */
    @PostMapping("/{modelVersionKey}/checkout")
    public ResponseEntity<ApiResponse<ModelInfoResponse>> checkoutBranchVersion(
            @PathVariable final long modelVersionKey,
            final Authentication authentication
    ) {
        final String currentUser = resolveCurrentUser(authentication);
        log.info("branch checkout 요청. modelVersionKey={}, currentUser={}", modelVersionKey, currentUser);

        final TcModel checkedOut = modelBranchCommandPort.checkoutBranchVersion(
                new ModelBranchCommandPort.CheckoutBranchVersionCommand(modelVersionKey, currentUser)
        );

        log.info("branch checkout 완료. sourceModelVersionKey={}, checkedOutModelVersionKey={}",
                modelVersionKey, checkedOut.modelVersionKey());
        return ResponseEntity.ok(ApiResponse.success(toModelInfoResponse(checkedOut)));
    }

    /**
     * EDIT version을 새 branch version으로 checkin합니다.
     *
     * @param modelVersionKey 체크인할 EDIT version key
     * @param request 새 버전/설명 요청 본문
     * @param authentication 현재 로그인 사용자 정보
     * @return 새로 생성된 branch 모델 버전 정보
     */
    @PostMapping("/{modelVersionKey}/checkin")
    public ResponseEntity<ApiResponse<ModelInfoResponse>> checkinBranchEditVersion(
            @PathVariable final long modelVersionKey,
            @Valid @RequestBody final ModelCheckinRequest request,
            final Authentication authentication
    ) {
        final String currentUser = resolveCurrentUser(authentication);
        log.info("branch checkin 요청. editModelVersionKey={}, newVersion={}, currentUser={}",
                modelVersionKey, request.newVersion(), currentUser);

        final TcModel checkedIn = modelBranchCommandPort.checkinBranchEditVersion(
                new ModelBranchCommandPort.CheckinBranchEditVersionCommand(
                        modelVersionKey,
                        request.newVersion(),
                        request.description(),
                        currentUser
                )
        );

        log.info("branch checkin 완료. editModelVersionKey={}, checkedInModelVersionKey={}",
                modelVersionKey, checkedIn.modelVersionKey());
        return ResponseEntity.ok(ApiResponse.success(toModelInfoResponse(checkedIn)));
    }

    /**
     * branch 최신 버전과 parent 최신 버전의 diff를 조회하고, 요청 시 실제 commit까지 수행합니다.
     *
     * <p>preview 요청은 본문 없이 호출할 수 있습니다.
     * 실제 commit은 {@code applyCommit=true}와 {@code newParentVersion}을 함께 전달해야 합니다.</p>
     *
     * @param modelKey 대상 branch model의 model_key
     * @param request preview/commit 요청 본문
     * @param authentication 현재 로그인 사용자 정보
     * @return diff 및 commit 결과
     */
    @PostMapping("/{modelKey}/commit-parent")
    public ResponseEntity<ApiResponse<ModelParentCommitResponse>> commitParent(
            @PathVariable final long modelKey,
            @RequestBody(required = false) final ModelParentCommitRequest request,
            final Authentication authentication
    ) {
        final String currentUser = resolveCurrentUser(authentication);
        final boolean applyCommit = request != null && Boolean.TRUE.equals(request.applyCommit());
        final String newParentVersion = request == null ? null : request.newParentVersion();

        log.info("parent commit 요청. branchModelKey={}, applyCommit={}, currentUser={}",
                modelKey, applyCommit, currentUser);

        final ModelParentCommitPort.CommitParentResult result = modelParentCommitPort.previewOrCommit(
                new ModelParentCommitPort.CommitParentCommand(modelKey, applyCommit, newParentVersion, currentUser)
        );

        log.info("parent commit 처리 완료. branchModelKey={}, committed={}", modelKey, result.committed());
        return ResponseEntity.ok(ApiResponse.success(toCommitParentResponse(result)));
    }

    /**
     * 선택된 root model에 연결된 deprecated branch를 일괄 삭제합니다.
     *
     * @param modelKey 부모 root model의 model_key
     * @return 삭제 결과
     */
    @DeleteMapping("/{modelKey}/branches/deprecated")
    public ResponseEntity<ApiResponse<ModelDeleteBatchResponse>> deleteDeprecatedBranches(
            @PathVariable final long modelKey
    ) {
        log.info("deprecated branch 일괄 삭제 요청. parentModelKey={}", modelKey);

        final ModelBranchCommandPort.DeleteDeprecatedBranchesResult result =
                modelBranchCommandPort.deleteDeprecatedBranches(modelKey);

        log.info("deprecated branch 일괄 삭제 완료. parentModelKey={}, deletedCount={}",
                modelKey, result.deletedCount());

        return ResponseEntity.ok(ApiResponse.success(new ModelDeleteBatchResponse(
                result.deletedCount(),
                result.deletedModelKeys(),
                result.deletedModelNames()
        )));
    }

    /**
     * 모델을 등록합니다.
     *
     * @param request 등록 요청 본문
     * @return 등록된 모델 정보
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ModelInfoResponse>> create(
            @Valid @RequestBody final ModelUpsertRequest request
    ) {
        log.info("모델 등록 요청. modelName={}, modelVersion={}, commInterface={}, status={}",
                request.modelName(), request.modelVersion(), request.commInterface(), request.status());

        final UpsertTcModel command = new UpsertTcModel(
                null,
                request.modelName(),
                null,
                request.modelVersion(),
                request.commInterface(),
                request.status(),
                request.description(),
                request.maker(),
                request.createdBy(),
                request.updatedBy()
        );

        final TcModel created = modelCrudPort.upsert(command);
        log.info("모델 등록 완료. modelVersionKey={}, modelName={}, modelVersion={}",
                created.modelVersionKey(), created.modelName(), created.modelVersion());

        return ResponseEntity.ok(ApiResponse.success(toModelInfoResponse(created)));
    }

    /**
     * 모델 정보를 수정합니다.
     *
     * @param modelVersionKey 수정 대상 모델 버전 키
     * @param request 수정 요청 본문
     * @return 수정된 모델 정보
     */
    @PutMapping("/{modelVersionKey}")
    public ResponseEntity<ApiResponse<ModelInfoResponse>> update(
            @PathVariable final long modelVersionKey,
            @Valid @RequestBody final ModelUpsertRequest request
    ) {
        log.info("모델 수정 요청. modelVersionKey={}, modelName={}, modelVersion={}",
                modelVersionKey, request.modelName(), request.modelVersion());

        final Optional<TcModel> existingModel = modelCrudPort.findByModelVersionKey(modelVersionKey);
        if (existingModel.isEmpty()) {
            log.warn("모델 수정 대상 없음. modelVersionKey={}", modelVersionKey);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "수정할 모델을 찾을 수 없습니다."));
        }
        final TcModel existing = existingModel.get();

        final UpsertTcModel command = new UpsertTcModel(
                modelVersionKey,
                request.modelName(),
                existing.parentModel(),
                request.modelVersion(),
                request.commInterface(),
                request.status(),
                request.description(),
                request.maker(),
                request.createdBy(),
                request.updatedBy()
        );

        final TcModel updated = modelCrudPort.upsert(command);
        log.info("모델 수정 완료. modelVersionKey={}, modelName={}, modelVersion={}",
                updated.modelVersionKey(), updated.modelName(), updated.modelVersion());

        return ResponseEntity.ok(ApiResponse.success(toModelInfoResponse(updated)));
    }

    /**
     * 모델을 삭제합니다.
     *
     * <p>legacy version delete와 신규 model delete가 같은 경로 패턴을 공유하므로,
     * {@code scope=model} 쿼리 파라미터가 있을 때만 model_key 기준 삭제를 수행합니다.
     * 파라미터가 없으면 기존과 동일하게 model_version_key 기준 삭제를 유지합니다.</p>
     *
     * @param modelIdentifier 삭제 대상 식별자
     * @param scope 삭제 범위. {@code model} 또는 {@code version}(기본값)
     * @return 삭제 성공 응답
     */
    @DeleteMapping("/{modelIdentifier}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable final long modelIdentifier,
            @RequestParam(name = "scope", required = false, defaultValue = "version") final String scope
    ) {
        final String normalizedScope = scope == null ? "version" : scope.trim().toLowerCase(Locale.ROOT);

        if ("model".equals(normalizedScope)) {
            log.info("model 단위 삭제 요청. modelKey={}", modelIdentifier);
            modelRootCommandPort.deleteModel(modelIdentifier);
            log.info("model 단위 삭제 완료. modelKey={}", modelIdentifier);
            return ResponseEntity.ok(ApiResponse.success(null));
        }

        if (!"version".equals(normalizedScope)) {
            throw new UiBadRequestException("scope는 version 또는 model만 허용됩니다.");
        }

        log.info("version 단위 삭제 요청. modelVersionKey={}", modelIdentifier);
        modelCrudPort.deleteByModelVersionKey(modelIdentifier);
        log.info("version 단위 삭제 완료. modelVersionKey={}", modelIdentifier);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * detailNode에 대응하는 상세 조회 응답을 생성합니다.
     *
     * <p>workflow 노드는 모델의 commInterface에 따라 다른 컬럼 세트를 반환합니다.
     * SOCKET 모델의 경우 SECS 전용인 EventId, TransactionId 컬럼을 제외합니다.</p>
     *
     * @param model 조회 대상 모델 (컬럼 분기 판단에 사용)
     * @param modelVersionKey 모델 버전 키
     * @param detailNode 상세 노드 식별자
     * @return 상세 조회 응답
     */
    private ModelDetailDataResponse buildDetailResponse(final TcModel model, final long modelVersionKey, final String detailNode) {
        final String normalizedNode = normalizeDetailNode(detailNode);
        return switch (normalizedNode) {
            case "model-parameter" -> new ModelDetailDataResponse(
                    MODEL_PARAMETER_COLUMNS,
                    modelDetailQueryPort.findParamsByModelVersionKey(modelVersionKey).stream()
                            .map(ModelController::toModelParamRow)
                            .toList(),
                    List.of()
            );
            case "secs-message" -> new ModelDetailDataResponse(
                    SECS_MESSAGE_COLUMNS,
                    modelDetailQueryPort.findSecsMessagesByModelVersionKey(modelVersionKey).stream()
                            .map(ModelController::toSecsMessageRow)
                            .toList(),
                    List.of()
            );
            case "socket-message" -> new ModelDetailDataResponse(
                    SOCKET_MESSAGE_COLUMNS,
                    modelDetailQueryPort.findSocketMessagesByModelVersionKey(modelVersionKey).stream()
                            .map(ModelController::toSocketMessageRow)
                            .toList(),
                    List.of()
            );
            case "mes-message" -> new ModelDetailDataResponse(
                    MES_MESSAGE_COLUMNS,
                    modelDetailQueryPort.findMesMessagesByModelVersionKey(modelVersionKey).stream()
                            .map(ModelController::toMesMessageRow)
                            .toList(),
                    List.of()
            );
            case "variableides" -> new ModelDetailDataResponse(
                    VARIABLE_ID_COLUMNS,
                    modelDetailQueryPort.findVariableIdsByModelVersionKey(modelVersionKey).stream()
                            .map(ModelController::toVariableIdRow)
                            .toList(),
                    List.of()
            );
            case "reportides" -> new ModelDetailDataResponse(
                    REPORT_ID_COLUMNS,
                    modelDetailQueryPort.findReportIdsByModelVersionKey(modelVersionKey).stream()
                            .map(ModelController::toReportIdRow)
                            .toList(),
                    List.of()
            );
            case "eventides" -> new ModelDetailDataResponse(
                    EVENT_ID_COLUMNS,
                    modelDetailQueryPort.findEventIdsByModelVersionKey(modelVersionKey).stream()
                            .map(ModelController::toEventIdRow)
                            .toList(),
                    List.of()
            );
            case "workflow" -> {
                // SOCKET 모델은 EventId, TransactionId 컬럼을 사용하지 않으므로 제외한 컬럼 세트를 반환
                final boolean socketModel = isSocketModel(model);
                final List<String> workflowColumns = socketModel ? SOCKET_WORKFLOW_COLUMNS : SECS_WORKFLOW_COLUMNS;
                yield new ModelDetailDataResponse(
                        workflowColumns,
                        modelDetailQueryPort.findWorkflowsByModelVersionKey(modelVersionKey).stream()
                                .map(workflow -> toWorkflowRow(workflow, socketModel))
                                .toList(),
                        List.of()
                );
            }
            case "mdf" -> new ModelDetailDataResponse(
                    List.of(),
                    List.of(),
                    modelDetailQueryPort.findMdfByModelVersionKey(modelVersionKey)
                            .map(ModelController::toMdfContent)
                            .stream()
                            .toList()
            );
            case "dcop-itemes" -> {
                // SOCKET 모델은 EventId 컬럼을 사용하지 않으므로 workflow와 동일한 방식으로 분기
                final boolean socketDcopModel = isSocketModel(model);
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
            default -> null;
        };
    }

    /**
     * detailNode path 값을 공통 규칙으로 정규화합니다.
     */
    private static String normalizeDetailNode(final String detailNode) {
        return detailNode == null ? "" : detailNode.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 상세 저장/업로드는 EDIT version에서만 허용합니다.
     */
    private static boolean isEditVersion(final TcModel model) {
        return model != null && EDIT_MODEL_VERSION.equalsIgnoreCase(nullToEmpty(model.modelVersion()));
    }

    /**
     * tc_model_param row를 상세 테이블 row DTO로 변환합니다.
     */
    private static ModelDetailRowResponse toModelParamRow(final TcModelParam param) {
        return new ModelDetailRowResponse(modelRowId("param", param.modelParamKey()), List.of(
                nullToEmpty(param.paramName()),
                nullToEmpty(param.paramValue()),
                nullToEmpty(param.description())
        ));
    }

    /**
     * tc_model_secs_message row를 상세 테이블 row DTO로 변환합니다.
     */
    private static ModelDetailRowResponse toSecsMessageRow(final TcModelSecsMessage message) {
        return new ModelDetailRowResponse(modelRowId("secs", message.secsMsgKey()), List.of(
                nullToEmpty(message.secsMsgName()),
                nullToEmpty(message.description()),
                nullToEmpty(message.dataIndex())
        ));
    }

    /**
     * tc_model_mes_message row를 상세 테이블 row DTO로 변환합니다.
     */
    private static ModelDetailRowResponse toMesMessageRow(final TcModelMesMessage message) {
        return new ModelDetailRowResponse(modelRowId("mes", message.mesMsgKey()), List.of(
                nullToEmpty(message.mesMsgName()),
                nullToEmpty(message.description()),
                nullToEmpty(message.dataIndex())
        ));
    }

    /**
     * tc_model_socket_message row를 상세 테이블 row DTO로 변환합니다.
     */
    private static ModelDetailRowResponse toSocketMessageRow(final TcModelSocketMessage message) {
        return new ModelDetailRowResponse(modelRowId("socket", message.socketMsgKey()), List.of(
                nullToEmpty(message.socketMsgName()),
                nullToEmpty(message.description()),
                nullToEmpty(message.dataIndex())
        ));
    }

    /**
     * tc_model_variableid row를 상세 테이블 row DTO로 변환합니다.
     */
    private static ModelDetailRowResponse toVariableIdRow(final TcModelVariableId variableId) {
        final String variableIdType = variableId.variableIdType() == null ? "" : variableId.variableIdType().name();
        return new ModelDetailRowResponse(modelRowId("variable", variableId.variableKey()), List.of(
                nullToEmpty(variableId.variableId()),
                variableIdType,
                nullToEmpty(variableId.description())
        ));
    }

    /**
     * tc_model_reportid row를 상세 테이블 row DTO로 변환합니다.
     */
    private static ModelDetailRowResponse toReportIdRow(final TcModelReportId reportId) {
        return new ModelDetailRowResponse(modelRowId("report", reportId.reportKey()), List.of(
                nullToEmpty(reportId.reportId()),
                nullToEmpty(reportId.variableId()),
                Boolean.toString(reportId.enabled()),
                nullToEmpty(reportId.description())
        ));
    }

    /**
     * tc_model_eventid row를 상세 테이블 row DTO로 변환합니다.
     */
    private static ModelDetailRowResponse toEventIdRow(final TcModelEventId eventId) {
        return new ModelDetailRowResponse(modelRowId("event", eventId.eventKey()), List.of(
                nullToEmpty(eventId.eventId()),
                nullToEmpty(eventId.reportId()),
                Boolean.toString(eventId.enabled()),
                nullToEmpty(eventId.description())
        ));
    }

    /**
     * tc_model_workflow row를 상세 테이블 row DTO로 변환합니다.
     */
    /**
     * workflow row를 상세 테이블 row DTO로 변환합니다.
     *
     * <p>SOCKET 모델의 경우 EventId, TransactionId를 사용하지 않으므로 해당 값을 제외합니다.</p>
     *
     * @param workflow 변환 대상 workflow 도메인 객체
     * @param excludeEventFields SOCKET 모델 여부. true이면 EventId, TransactionId를 values에서 제외
     * @return 상세 테이블 row DTO
     */
    private static ModelDetailRowResponse toWorkflowRow(final TcModelWorkflow workflow, final boolean excludeEventFields) {
        final List<String> values;
        if (excludeEventFields) {
            // SOCKET 컬럼 순서: [workflowName, messageName, filter, actionName, dataIndex]
            values = List.of(
                    nullToEmpty(workflow.workflowName()),
                    nullToEmpty(workflow.messageName()),
                    nullToEmpty(workflow.workflowFilter()),
                    nullToEmpty(workflow.actionName()),
                    nullToEmpty(workflow.actionDataIndex())
            );
        } else {
            // SECS 컬럼 순서: [workflowName, messageName, eventId, transactionId, filter, actionName, dataIndex]
            values = List.of(
                    nullToEmpty(workflow.workflowName()),
                    nullToEmpty(workflow.messageName()),
                    nullToEmpty(workflow.eventId()),
                    nullToEmpty(workflow.transactionId()),
                    nullToEmpty(workflow.workflowFilter()),
                    nullToEmpty(workflow.actionName()),
                    nullToEmpty(workflow.actionDataIndex())
            );
        }
        return new ModelDetailRowResponse(
                modelRowId("workflow", workflow.workflowKey()),
                values,
                ModelDetailPreviewSupport.buildWorkflowPreviewValues(workflow)
        );
    }

    /**
     * tc_model_dcop_item row를 상세 테이블 row DTO로 변환합니다.
     *
     * <p>SOCKET 모델의 경우 EventId를 사용하지 않으므로 excludeEventId=true를 전달하면
     * EventId 값을 values에서 제외합니다.</p>
     *
     * @param dcopItem 변환 대상 dcop item 도메인 객체
     * @param excludeEventId SOCKET 모델 여부. true이면 EventId를 values에서 제외
     * @return 상세 테이블 row DTO
     */
    private static ModelDetailRowResponse toDcopItemRow(final TcModelDcopItem dcopItem, final boolean excludeEventId) {
        final String collectionRule = dcopItem.collectionRule() == null ? "" : dcopItem.collectionRule().name();
        final String calculationRule = dcopItem.calculationRule() == null ? "" : dcopItem.calculationRule();
        final String orderRule = dcopItem.orderRule() == null ? "" : dcopItem.orderRule().toString();
        final List<String> values;
        if (excludeEventId) {
            // SOCKET 컬럼 순서: [dcopItemName, workflowName, variableId, collectionRule, calculationRule, orderRule]
            values = List.of(
                    nullToEmpty(dcopItem.dcopItemName()),
                    nullToEmpty(dcopItem.workflowName()),
                    nullToEmpty(dcopItem.variableId()),
                    collectionRule,
                    calculationRule,
                    orderRule
            );
        } else {
            // SECS 컬럼 순서: [dcopItemName, workflowName, eventId, variableId, collectionRule, calculationRule, orderRule]
            values = List.of(
                    nullToEmpty(dcopItem.dcopItemName()),
                    nullToEmpty(dcopItem.workflowName()),
                    nullToEmpty(dcopItem.eventId()),
                    nullToEmpty(dcopItem.variableId()),
                    collectionRule,
                    calculationRule,
                    orderRule
            );
        }
        return new ModelDetailRowResponse(modelRowId("dcop", dcopItem.dcopItemKey()), values);
    }

    /**
     * tc_model_mdf row를 MDF(XML) DTO로 변환합니다.
     */
    private static ModelMdfContentResponse toMdfContent(final TcModelMdf mdf) {
        final byte[] mdfFile = (mdf.mdfFile() == null) ? new byte[0] : mdf.mdfFile();
        final String xml = new String(mdfFile, StandardCharsets.UTF_8);
        return new ModelMdfContentResponse(nullToEmpty(mdf.mdfName()), xml);
    }

    /**
     * row values 리스트에서 지정 인덱스 값을 null-safe하게 읽습니다.
     *
     * @param values row values
     * @param index 읽을 컬럼 인덱스
     * @return 존재하면 해당 값, 없으면 null
     */
    private static String valueAt(final List<String> values, final int index) {
        if (values == null || index < 0 || index >= values.size()) {
            return null;
        }
        return values.get(index);
    }

    /**
     * 일반 상세 row 식별자를 prefix + key 형식으로 생성합니다.
     */
    private static String modelRowId(final String prefix, final Long key) {
        if (key == null || key <= 0L) {
            return "";
        }
        return prefix + ":" + key;
    }

    /**
     * null 문자열을 빈 문자열로 변환합니다.
     */
    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }

    /**
     * multipart 업로드 바이트를 읽고 입력 오류를 정규화합니다.
     */
    private static byte[] readUploadedBytes(final MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new UiBadRequestException("업로드할 MDF 파일이 비어 있습니다.");
        }

        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UiBadRequestException("업로드한 MDF 파일을 읽을 수 없습니다.", e);
        }
    }

    /**
     * 업로드 시 사용할 MDF 이름을 결정합니다.
     *
     * <p>우선순위:
     * 1) 요청 파라미터
     * 2) 기존 저장 MDF 이름
     * 3) 업로드 파일명(확장자 제거)
     * 4) 기본값</p>
     */
    private static String resolveUploadMdfName(
            final String requestedMdfName,
            final String originalFilename,
            final TcModelMdf existingMdf
    ) {
        final String explicitName = normalizeOptionalText(requestedMdfName);
        if (explicitName != null) {
            return explicitName;
        }

        final String existingName = existingMdf == null ? null : normalizeOptionalText(existingMdf.mdfName());
        if (existingName != null) {
            return existingName;
        }

        final String fileBasedName = extractBaseName(originalFilename);
        if (fileBasedName != null) {
            return fileBasedName;
        }
        return DEFAULT_MDF_NAME;
    }

    /**
     * 선택 문자열을 trim 기반으로 정규화합니다.
     */
    private static String normalizeOptionalText(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 파일명에서 마지막 확장자를 제거한 base name을 추출합니다.
     */
    private static String extractBaseName(final String filename) {
        final String normalizedFilename = normalizeOptionalText(filename);
        if (normalizedFilename == null) {
            return null;
        }

        final int lastSlash = Math.max(normalizedFilename.lastIndexOf('/'), normalizedFilename.lastIndexOf('\\'));
        final String simpleName = lastSlash >= 0 ? normalizedFilename.substring(lastSlash + 1) : normalizedFilename;
        final int extensionIndex = simpleName.lastIndexOf('.');
        if (extensionIndex <= 0) {
            return simpleName;
        }
        return simpleName.substring(0, extensionIndex);
    }

    /**
     * 도메인 페이지 응답을 API 응답 DTO 페이지로 변환합니다.
     *
     * @param page 도메인 페이지 응답
     * @return API 응답 페이지
     */
    private static PagedResponse<ModelInfoResponse> toModelPage(final PagedResponse<TcModel> page) {
        final List<ModelInfoResponse> items = page.items().stream()
                .map(ModelController::toModelInfoResponse)
                .toList();
        return PagedResponse.of(items, page.offset(), page.limit(), page.count());
    }

    /**
     * 도메인 모델을 응답 DTO로 변환합니다.
     *
     * @param model 도메인 모델
     * @return 응답 DTO
     */
    private static ModelInfoResponse toModelInfoResponse(final TcModel model) {
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
    private static ModelParentCommitResponse toCommitParentResponse(
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
                        .map(ModelController::toDiffSectionResponse)
                        .toList()
        );
    }

    /**
     * core diff 섹션을 API 응답 DTO로 변환합니다.
     */
    private static ModelDiffSectionResponse toDiffSectionResponse(
            final ModelParentCommitPort.DiffSection section
    ) {
        return new ModelDiffSectionResponse(
                section.detailNode(),
                section.columns(),
                section.added().stream().map(ModelController::toDiffItemResponse).toList(),
                section.changed().stream().map(ModelController::toDiffItemResponse).toList(),
                section.deleted().stream().map(ModelController::toDiffItemResponse).toList()
        );
    }

    /**
     * core diff 항목을 API 응답 DTO로 변환합니다.
     */
    private static ModelDiffItemResponse toDiffItemResponse(
            final ModelParentCommitPort.DiffItem item
    ) {
        return new ModelDiffItemResponse(
                item.identity(),
                item.branchValues(),
                item.parentValues()
        );
    }

    /**
     * 인증 정보에서 현재 사용자 ID를 추출합니다.
     */
    private static String resolveCurrentUser(final Authentication authentication) {
        if (authentication == null) {
            return "SYSTEM";
        }
        if (authentication.getPrincipal() instanceof UserPrincipal principal
                && principal.userId() != null
                && !principal.userId().isBlank()) {
            return principal.userId();
        }
        if (authentication.getName() == null || authentication.getName().isBlank()) {
            return "SYSTEM";
        }
        return authentication.getName();
    }
}
