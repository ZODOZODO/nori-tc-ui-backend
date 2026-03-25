package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.controller.support.ModelControllerRequestSupport;
import com.nori.tc.ui.adapters.web.controller.support.ModelDetailResponseSupport;
import com.nori.tc.ui.adapters.web.dto.request.ModelDetailSaveRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelDetailDataResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelMdfContentResponse;
import com.nori.tc.ui.core.port.db.ModelCrudPort;
import com.nori.tc.ui.core.port.db.ModelDetailCommandPort;
import com.nori.tc.ui.core.port.db.ModelDetailQueryPort;
import com.nori.tc.ui.domain.model.UiModel;
import com.nori.tc.ui.domain.model.UiModelMdf;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 모델 상세 저장/MDF 업로드 계층을 분리합니다.
 */
@RestController
@RequestMapping("/api/model")
public class ModelDetailController {

    private static final Logger log = LoggerFactory.getLogger(ModelDetailController.class);

    private final ModelCrudPort modelCrudPort;
    private final ModelDetailQueryPort modelDetailQueryPort;
    private final ModelDetailCommandPort modelDetailCommandPort;

    public ModelDetailController(
            final ModelCrudPort modelCrudPort,
            final ModelDetailQueryPort modelDetailQueryPort,
            final ModelDetailCommandPort modelDetailCommandPort
    ) {
        this.modelCrudPort = Objects.requireNonNull(modelCrudPort, "modelCrudPort is null");
        this.modelDetailQueryPort = Objects.requireNonNull(modelDetailQueryPort, "modelDetailQueryPort is null");
        this.modelDetailCommandPort = Objects.requireNonNull(modelDetailCommandPort, "modelDetailCommandPort is null");
    }

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

        final UiModel model = optionalModel.get();
        final boolean isSocketWorkflow = "workflow".equals(normalizedNode)
                && ModelControllerRequestSupport.isSocketModel(model);
        final boolean isSocketDcopItem = "dcop-itemes".equals(normalizedNode)
                && ModelControllerRequestSupport.isSocketModel(model);

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

        final UiModelMdf saved = modelDetailCommandPort.saveMdf(modelVersionKey, resolvedMdfName, fileBytes);
        return ResponseEntity.ok(ApiResponse.success(ModelDetailResponseSupport.toMdfContent(saved)));
    }
}
