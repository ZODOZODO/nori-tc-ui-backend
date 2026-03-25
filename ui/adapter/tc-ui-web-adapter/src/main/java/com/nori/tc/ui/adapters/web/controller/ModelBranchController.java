package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.controller.support.ModelControllerRequestSupport;
import com.nori.tc.ui.adapters.web.controller.support.ModelDetailResponseSupport;
import com.nori.tc.ui.adapters.web.dto.request.ModelBranchCreateRequest;
import com.nori.tc.ui.adapters.web.dto.request.ModelCheckinRequest;
import com.nori.tc.ui.adapters.web.dto.request.ModelInfoUpdateRequest;
import com.nori.tc.ui.adapters.web.dto.request.ModelParentCommitRequest;
import com.nori.tc.ui.adapters.web.dto.request.ModelRootCreateRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelDeleteBatchResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelInfoResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelParentCommitResponse;
import com.nori.tc.ui.core.port.db.ModelBranchCommandPort;
import com.nori.tc.ui.core.port.db.ModelParentCommitPort;
import com.nori.tc.ui.core.port.db.ModelRootCommandPort;
import com.nori.tc.ui.domain.model.UiModel;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * 모델 root/branch/version 관련 계층을 분리합니다.
 */
@RestController
@RequestMapping("/api/model")
public class ModelBranchController {

    private static final Logger log = LoggerFactory.getLogger(ModelBranchController.class);

    private final ModelRootCommandPort modelRootCommandPort;
    private final ModelBranchCommandPort modelBranchCommandPort;
    private final ModelParentCommitPort modelParentCommitPort;

    public ModelBranchController(
            final ModelRootCommandPort modelRootCommandPort,
            final ModelBranchCommandPort modelBranchCommandPort,
            final ModelParentCommitPort modelParentCommitPort
    ) {
        this.modelRootCommandPort = Objects.requireNonNull(modelRootCommandPort, "modelRootCommandPort is null");
        this.modelBranchCommandPort = Objects.requireNonNull(modelBranchCommandPort, "modelBranchCommandPort is null");
        this.modelParentCommitPort = Objects.requireNonNull(modelParentCommitPort, "modelParentCommitPort is null");
    }

    @PostMapping("/roots")
    public ResponseEntity<ApiResponse<ModelInfoResponse>> createRoot(
            @Valid @RequestBody final ModelRootCreateRequest request,
            final Authentication authentication
    ) {
        final String currentUser = ModelControllerRequestSupport.resolveCurrentUser(authentication);
        final UiModel created = modelRootCommandPort.createRootModel(new ModelRootCommandPort.CreateRootModelCommand(
                request.modelName(),
                request.commInterface(),
                request.maker(),
                currentUser
        ));
        return ResponseEntity.ok(ApiResponse.success(ModelDetailResponseSupport.toModelInfoResponse(created)));
    }

    @PutMapping("/{modelKey}/info")
    public ResponseEntity<ApiResponse<ModelInfoResponse>> updateModelInfo(
            @PathVariable final long modelKey,
            @Valid @RequestBody final ModelInfoUpdateRequest request,
            final Authentication authentication
    ) {
        final String currentUser = ModelControllerRequestSupport.resolveCurrentUser(authentication);
        final UiModel updated = modelRootCommandPort.updateRootModelInfo(
                new ModelRootCommandPort.UpdateRootModelInfoCommand(modelKey, request.maker(), currentUser)
        );
        return ResponseEntity.ok(ApiResponse.success(ModelDetailResponseSupport.toModelInfoResponse(updated)));
    }

    @PostMapping("/{modelKey}/branches")
    public ResponseEntity<ApiResponse<ModelInfoResponse>> createBranch(
            @PathVariable final long modelKey,
            @Valid @RequestBody final ModelBranchCreateRequest request,
            final Authentication authentication
    ) {
        final String currentUser = ModelControllerRequestSupport.resolveCurrentUser(authentication);
        final UiModel created = modelBranchCommandPort.createBranchModel(
                new ModelBranchCommandPort.CreateBranchModelCommand(
                        modelKey,
                        request.suffix(),
                        request.sourceModelVersionKey(),
                        currentUser
                )
        );
        return ResponseEntity.ok(ApiResponse.success(ModelDetailResponseSupport.toModelInfoResponse(created)));
    }

    @PostMapping("/{modelVersionKey}/checkout")
    public ResponseEntity<ApiResponse<ModelInfoResponse>> checkoutBranchVersion(
            @PathVariable final long modelVersionKey,
            final Authentication authentication
    ) {
        final String currentUser = ModelControllerRequestSupport.resolveCurrentUser(authentication);
        final UiModel checkedOut = modelBranchCommandPort.checkoutBranchVersion(
                new ModelBranchCommandPort.CheckoutBranchVersionCommand(modelVersionKey, currentUser)
        );
        return ResponseEntity.ok(ApiResponse.success(ModelDetailResponseSupport.toModelInfoResponse(checkedOut)));
    }

    @PostMapping("/{modelVersionKey}/checkin")
    public ResponseEntity<ApiResponse<ModelInfoResponse>> checkinBranchEditVersion(
            @PathVariable final long modelVersionKey,
            @Valid @RequestBody final ModelCheckinRequest request,
            final Authentication authentication
    ) {
        final String currentUser = ModelControllerRequestSupport.resolveCurrentUser(authentication);
        final UiModel checkedIn = modelBranchCommandPort.checkinBranchEditVersion(
                new ModelBranchCommandPort.CheckinBranchEditVersionCommand(
                        modelVersionKey,
                        request.newVersion(),
                        request.description(),
                        currentUser
                )
        );
        return ResponseEntity.ok(ApiResponse.success(ModelDetailResponseSupport.toModelInfoResponse(checkedIn)));
    }

    @PostMapping("/{modelKey}/commit-parent")
    public ResponseEntity<ApiResponse<ModelParentCommitResponse>> commitParent(
            @PathVariable final long modelKey,
            @RequestBody(required = false) final ModelParentCommitRequest request,
            final Authentication authentication
    ) {
        final String currentUser = ModelControllerRequestSupport.resolveCurrentUser(authentication);
        final boolean applyCommit = request != null && Boolean.TRUE.equals(request.applyCommit());
        final String newParentVersion = request == null ? null : request.newParentVersion();
        final ModelParentCommitPort.CommitParentResult result = modelParentCommitPort.previewOrCommit(
                new ModelParentCommitPort.CommitParentCommand(modelKey, applyCommit, newParentVersion, currentUser)
        );
        return ResponseEntity.ok(ApiResponse.success(ModelDetailResponseSupport.toCommitParentResponse(result)));
    }

    @DeleteMapping("/{modelKey}/branches/deprecated")
    public ResponseEntity<ApiResponse<ModelDeleteBatchResponse>> deleteDeprecatedBranches(
            @PathVariable final long modelKey
    ) {
        log.info("deprecated branch 일괄 삭제 요청. parentModelKey={}", modelKey);
        final ModelBranchCommandPort.DeleteDeprecatedBranchesResult result =
                modelBranchCommandPort.deleteDeprecatedBranches(modelKey);
        return ResponseEntity.ok(ApiResponse.success(new ModelDeleteBatchResponse(
                result.deletedCount(),
                result.deletedModelKeys(),
                result.deletedModelNames()
        )));
    }
}
