package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.db.core.model.upsert.UpsertTcModel;
import com.nori.tc.ui.adapters.web.dto.request.ModelUpsertRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelInfoResponse;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.port.db.ModelCrudPort;
import com.nori.tc.ui.core.port.db.ModelRootCommandPort;
import com.nori.tc.ui.domain.model.UiModel;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * 모델 일반 CRUD 계층을 분리합니다.
 */
@RestController
@RequestMapping("/api/model")
public class ModelMutationController {

    private static final Logger log = LoggerFactory.getLogger(ModelMutationController.class);

    private final ModelCrudPort modelCrudPort;
    private final ModelRootCommandPort modelRootCommandPort;

    public ModelMutationController(
            final ModelCrudPort modelCrudPort,
            final ModelRootCommandPort modelRootCommandPort
    ) {
        this.modelCrudPort = Objects.requireNonNull(modelCrudPort, "modelCrudPort is null");
        this.modelRootCommandPort = Objects.requireNonNull(modelRootCommandPort, "modelRootCommandPort is null");
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ModelInfoResponse>> create(
            @Valid @RequestBody final ModelUpsertRequest request
    ) {
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
        return ResponseEntity.ok(ApiResponse.success(
                com.nori.tc.ui.adapters.web.controller.support.ModelDetailResponseSupport.toModelInfoResponse(created)
        ));
    }

    @PutMapping("/{modelVersionKey}")
    public ResponseEntity<ApiResponse<ModelInfoResponse>> update(
            @PathVariable final long modelVersionKey,
            @Valid @RequestBody final ModelUpsertRequest request
    ) {
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
        return ResponseEntity.ok(ApiResponse.success(
                com.nori.tc.ui.adapters.web.controller.support.ModelDetailResponseSupport.toModelInfoResponse(updated)
        ));
    }

    @DeleteMapping("/{modelIdentifier}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable final long modelIdentifier,
            @RequestParam(name = "scope", required = false, defaultValue = "version") final String scope
    ) {
        final String normalizedScope = scope == null ? "version" : scope.trim().toLowerCase(Locale.ROOT);
        if ("model".equals(normalizedScope)) {
            modelRootCommandPort.deleteModel(modelIdentifier);
            return ResponseEntity.ok(ApiResponse.success(null));
        }
        if (!"version".equals(normalizedScope)) {
            throw new UiBadRequestException("scope는 version 또는 model만 허용됩니다.");
        }

        modelCrudPort.deleteByModelVersionKey(modelIdentifier);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
