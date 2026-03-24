package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.model.upsert.UpsertTcModel;
import com.nori.tc.ui.adapters.web.controller.support.ModelControllerRequestSupport;
import com.nori.tc.ui.adapters.web.controller.support.ModelDetailResponseSupport;
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
import com.nori.tc.ui.adapters.web.dto.response.ModelDeleteBatchResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelInfoResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelMdfContentResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelParentCommitResponse;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.port.db.ModelBranchCommandPort;
import com.nori.tc.ui.core.port.db.ModelCrudPort;
import com.nori.tc.ui.core.port.db.ModelDetailCommandPort;
import com.nori.tc.ui.core.port.db.ModelDetailQueryPort;
import com.nori.tc.ui.core.port.db.ModelParentCommitPort;
import com.nori.tc.ui.core.port.db.ModelRootCommandPort;
import com.nori.tc.ui.domain.model.UiModel;
import com.nori.tc.ui.domain.model.UiModelMdf;
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

        final PagedResponse<UiModel> page = modelCrudPort.findAll(pageRequest);
        final PagedResponse<ModelInfoResponse> responsePage = ModelDetailResponseSupport.toModelPage(page);

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

        final Optional<UiModel> optionalModel = modelCrudPort.findByModelVersionKey(modelVersionKey);
        if (optionalModel.isEmpty()) {
            log.warn("모델 단건 조회 결과 없음. modelVersionKey={}", modelVersionKey);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "모델을 찾을 수 없습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(ModelDetailResponseSupport.toModelInfoResponse(optionalModel.get())));
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

        final Optional<UiModel> optionalModelForDetail = modelCrudPort.findByModelVersionKey(modelVersionKey);
        if (optionalModelForDetail.isEmpty()) {
            log.warn("모델 상세 노드 조회 결과 없음 - 모델 미존재. modelVersionKey={}, detailNode={}", modelVersionKey, detailNode);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "모델을 찾을 수 없습니다."));
        }

        final ModelDetailDataResponse response = ModelDetailResponseSupport.buildDetailResponse(
                modelDetailQueryPort,
                optionalModelForDetail.get(),
                modelVersionKey,
                detailNode
        );

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
        final Optional<UiModel> optionalModel = modelCrudPort.findByModelVersionKey(modelVersionKey);
        if (optionalModel.isEmpty()) {
            log.warn("모델 상세 저장 대상 없음. modelVersionKey={}, detailNode={}", modelVersionKey, detailNode);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "모델을 찾을 수 없습니다."));
        }
        if (!ModelControllerRequestSupport.isEditVersion(optionalModel.get())) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("INVALID_MODEL_VERSION", "EDIT version만 저장할 수 있습니다."));
        }

        final String normalizedNode = ModelControllerRequestSupport.normalizeDetailNode(detailNode);
        if ("mdf".equals(normalizedNode)) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("INVALID_NODE", "MDF는 upload API를 사용해 주세요."));
        }

        log.info("모델 상세 row 저장 요청. modelVersionKey={}, detailNode={}, rowCount={}",
                modelVersionKey, normalizedNode, request.rows() == null ? 0 : request.rows().size());

        final UiModel model = optionalModel.get();
        final boolean isSocketWorkflow = "workflow".equals(normalizedNode)
                && ModelControllerRequestSupport.isSocketModel(model);
        final boolean isSocketDcopItem = "dcop-itemes".equals(normalizedNode)
                && ModelControllerRequestSupport.isSocketModel(model);

        // SOCKET 모델의 workflow는 5개 컬럼(EventId/TransactionId 제외)으로 전송됩니다.
        // SOCKET 모델의 dcop-itemes는 6개 컬럼(EventId 제외)으로 전송됩니다.
        // 두 경우 모두 DB 저장 계층이 기대하는 SECS 기준 values 형식으로 확장합니다.
        final List<ModelDetailSaveRequest.ModelDetailSaveRowItem> normalizedRows;
        if (isSocketWorkflow) {
            normalizedRows = ModelControllerRequestSupport.expandSocketWorkflowValues(request.rows());
        } else if (isSocketDcopItem) {
            normalizedRows = ModelControllerRequestSupport.expandSocketDcopItemValues(request.rows());
        } else {
            normalizedRows = request.rows();
        }

        final String workflowValidationMessage = ModelControllerRequestSupport.validateWorkflowJsonRows(
                log,
                normalizedNode,
                normalizedRows,
                isSocketWorkflow
        );
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

        final ModelDetailDataResponse response = ModelDetailResponseSupport.buildDetailResponse(
                modelDetailQueryPort,
                model,
                modelVersionKey,
                normalizedNode
        );
        if (response == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("INVALID_NODE", "지원하지 않는 상세 노드입니다."));
        }
        return ResponseEntity.ok(ApiResponse.success(response));
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
        final Optional<UiModel> optionalModel = modelCrudPort.findByModelVersionKey(modelVersionKey);
        if (optionalModel.isEmpty()) {
            log.warn("MDF 업로드 대상 모델 없음. modelVersionKey={}", modelVersionKey);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "모델을 찾을 수 없습니다."));
        }
        if (!ModelControllerRequestSupport.isEditVersion(optionalModel.get())) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("INVALID_MODEL_VERSION", "EDIT version만 업로드할 수 있습니다."));
        }

        final String resolvedMdfName = ModelControllerRequestSupport.resolveUploadMdfName(
                mdfName,
                file == null ? null : file.getOriginalFilename(),
                modelDetailQueryPort.findMdfByModelVersionKey(modelVersionKey)
                        .map(UiModelMdf::mdfName)
                        .orElse(null)
        );
        final byte[] fileBytes = ModelControllerRequestSupport.readUploadedBytes(file);

        log.info("MDF 업로드 요청. modelVersionKey={}, mdfName={}, fileName={}, fileSize={}",
                modelVersionKey,
                resolvedMdfName,
                file == null ? "" : file.getOriginalFilename(),
                fileBytes.length);

        final UiModelMdf saved = modelDetailCommandPort.saveMdf(modelVersionKey, resolvedMdfName, fileBytes);
        return ResponseEntity.ok(ApiResponse.success(ModelDetailResponseSupport.toMdfContent(saved)));
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
        final String currentUser = ModelControllerRequestSupport.resolveCurrentUser(authentication);
        log.info("root model 생성 요청. modelName={}, commInterface={}, currentUser={}",
                request.modelName(), request.commInterface(), currentUser);

        final UiModel created = modelRootCommandPort.createRootModel(new ModelRootCommandPort.CreateRootModelCommand(
                request.modelName(),
                request.commInterface(),
                request.maker(),
                currentUser
        ));

        log.info("root model 생성 완료. modelKey={}, modelName={}", created.modelKey(), created.modelName());
        return ResponseEntity.ok(ApiResponse.success(ModelDetailResponseSupport.toModelInfoResponse(created)));
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
        final String currentUser = ModelControllerRequestSupport.resolveCurrentUser(authentication);
        log.info("root model 정보 수정 요청. modelKey={}, currentUser={}", modelKey, currentUser);

        final UiModel updated = modelRootCommandPort.updateRootModelInfo(
                new ModelRootCommandPort.UpdateRootModelInfoCommand(modelKey, request.maker(), currentUser)
        );

        log.info("root model 정보 수정 완료. modelKey={}, modelName={}", updated.modelKey(), updated.modelName());
        return ResponseEntity.ok(ApiResponse.success(ModelDetailResponseSupport.toModelInfoResponse(updated)));
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
        final String currentUser = ModelControllerRequestSupport.resolveCurrentUser(authentication);
        log.info("branch model 생성 요청. parentModelKey={}, sourceModelVersionKey={}, suffix={}, currentUser={}",
                modelKey, request.sourceModelVersionKey(), request.suffix(), currentUser);

        final UiModel created = modelBranchCommandPort.createBranchModel(
                new ModelBranchCommandPort.CreateBranchModelCommand(
                        modelKey,
                        request.suffix(),
                        request.sourceModelVersionKey(),
                        currentUser
                )
        );

        log.info("branch model 생성 완료. branchModelKey={}, branchModelName={}", created.modelKey(), created.modelName());
        return ResponseEntity.ok(ApiResponse.success(ModelDetailResponseSupport.toModelInfoResponse(created)));
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
        final String currentUser = ModelControllerRequestSupport.resolveCurrentUser(authentication);
        log.info("branch checkout 요청. modelVersionKey={}, currentUser={}", modelVersionKey, currentUser);

        final UiModel checkedOut = modelBranchCommandPort.checkoutBranchVersion(
                new ModelBranchCommandPort.CheckoutBranchVersionCommand(modelVersionKey, currentUser)
        );

        log.info("branch checkout 완료. sourceModelVersionKey={}, checkedOutModelVersionKey={}",
                modelVersionKey, checkedOut.modelVersionKey());
        return ResponseEntity.ok(ApiResponse.success(ModelDetailResponseSupport.toModelInfoResponse(checkedOut)));
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
        final String currentUser = ModelControllerRequestSupport.resolveCurrentUser(authentication);
        log.info("branch checkin 요청. editModelVersionKey={}, newVersion={}, currentUser={}",
                modelVersionKey, request.newVersion(), currentUser);

        final UiModel checkedIn = modelBranchCommandPort.checkinBranchEditVersion(
                new ModelBranchCommandPort.CheckinBranchEditVersionCommand(
                        modelVersionKey,
                        request.newVersion(),
                        request.description(),
                        currentUser
                )
        );

        log.info("branch checkin 완료. editModelVersionKey={}, checkedInModelVersionKey={}",
                modelVersionKey, checkedIn.modelVersionKey());
        return ResponseEntity.ok(ApiResponse.success(ModelDetailResponseSupport.toModelInfoResponse(checkedIn)));
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
        final String currentUser = ModelControllerRequestSupport.resolveCurrentUser(authentication);
        final boolean applyCommit = request != null && Boolean.TRUE.equals(request.applyCommit());
        final String newParentVersion = request == null ? null : request.newParentVersion();

        log.info("parent commit 요청. branchModelKey={}, applyCommit={}, currentUser={}",
                modelKey, applyCommit, currentUser);

        final ModelParentCommitPort.CommitParentResult result = modelParentCommitPort.previewOrCommit(
                new ModelParentCommitPort.CommitParentCommand(modelKey, applyCommit, newParentVersion, currentUser)
        );

        log.info("parent commit 처리 완료. branchModelKey={}, committed={}", modelKey, result.committed());
        return ResponseEntity.ok(ApiResponse.success(ModelDetailResponseSupport.toCommitParentResponse(result)));
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

        final UiModel created = modelCrudPort.upsert(command);
        log.info("모델 등록 완료. modelVersionKey={}, modelName={}, modelVersion={}",
                created.modelVersionKey(), created.modelName(), created.modelVersion());

        return ResponseEntity.ok(ApiResponse.success(ModelDetailResponseSupport.toModelInfoResponse(created)));
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

        final Optional<UiModel> existingModel = modelCrudPort.findByModelVersionKey(modelVersionKey);
        if (existingModel.isEmpty()) {
            log.warn("모델 수정 대상 없음. modelVersionKey={}", modelVersionKey);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "수정할 모델을 찾을 수 없습니다."));
        }
        final UiModel existing = existingModel.get();

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

        final UiModel updated = modelCrudPort.upsert(command);
        log.info("모델 수정 완료. modelVersionKey={}, modelName={}, modelVersion={}",
                updated.modelVersionKey(), updated.modelName(), updated.modelVersion());

        return ResponseEntity.ok(ApiResponse.success(ModelDetailResponseSupport.toModelInfoResponse(updated)));
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

}
