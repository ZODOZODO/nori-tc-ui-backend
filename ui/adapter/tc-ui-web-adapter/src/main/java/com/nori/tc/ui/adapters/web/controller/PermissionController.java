package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.application.admin.PermissionAdminFacade;
import com.nori.tc.ui.adapters.web.controller.support.UiPageRequestSupport;
import com.nori.tc.ui.adapters.web.dto.request.PermissionUpsertRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.PermissionInfoResponse;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * UI 권한(Permission) 관리 REST API 컨트롤러입니다.
 *
 * <p>제공 엔드포인트:</p>
 * <ul>
 *   <li>GET /api/permission</li>
 *   <li>GET /api/permission/{permId}</li>
 *   <li>POST /api/permission</li>
 *   <li>PUT /api/permission/{permId}</li>
 *   <li>DELETE /api/permission/{permId}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/permission")
public class PermissionController {

    private static final Logger log = LoggerFactory.getLogger(PermissionController.class);

    private final PermissionAdminFacade permissionAdminFacade;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * @param permissionAdminFacade 권한 관리 facade
     */
    public PermissionController(final PermissionAdminFacade permissionAdminFacade) {
        this.permissionAdminFacade = Objects.requireNonNull(permissionAdminFacade, "permissionAdminFacade is null");
    }

    /**
     * 권한 목록을 페이지 단위로 조회합니다.
     *
     * @param offset 조회 시작 위치(기본값 0)
     * @param limit 조회 건수(기본값 100, 최대 500)
     * @return 목록 페이지 응답
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<PermissionInfoResponse>>> list(
            @RequestParam(name = "offset", required = false) final Integer offset,
            @RequestParam(name = "limit", required = false) final Integer limit
    ) {
        final UiPageRequest pageRequest = UiPageRequestSupport.resolve(offset, limit);

        if (log.isDebugEnabled()) {
            log.debug("권한 목록 조회 요청. offset={}, limit={}", pageRequest.offset(), pageRequest.limit());
        }

        final PagedResponse<PermissionInfoResponse> responsePage = permissionAdminFacade.list(pageRequest);

        if (log.isDebugEnabled()) {
            log.debug("권한 목록 조회 완료. offset={}, limit={}, pageSize={}, totalCount={}",
                    responsePage.offset(), responsePage.limit(), responsePage.items().size(), responsePage.count());
        }

        return ResponseEntity.ok(ApiResponse.success(responsePage));
    }

    /**
     * 권한 PK 기준으로 단건을 조회합니다.
     *
     * @param permId 권한 PK
     * @return 단건 조회 응답
     */
    @GetMapping("/{permId}")
    public ResponseEntity<ApiResponse<PermissionInfoResponse>> get(
            @PathVariable final long permId
    ) {
        if (log.isDebugEnabled()) {
            log.debug("권한 단건 조회 요청. permId={}", permId);
        }

        final var optionalPermission = permissionAdminFacade.findByPermId(permId);
        if (optionalPermission.isEmpty()) {
            log.warn("권한 단건 조회 결과 없음. permId={}", permId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "권한을 찾을 수 없습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(optionalPermission.get()));
    }

    /**
     * 권한을 등록합니다.
     *
     * @param request 등록 요청 본문
     * @return 등록된 권한 정보
     */
    @PostMapping
    public ResponseEntity<ApiResponse<PermissionInfoResponse>> create(
            @Valid @RequestBody final PermissionUpsertRequest request
    ) {
        log.info("권한 등록 요청. permCode={}, resourceType={}, resource={}, httpMethod={}",
                request.permCode(), request.resourceType(), request.resource(), request.httpMethod());

        final PermissionInfoResponse created = permissionAdminFacade.create(request);
        log.info("권한 등록 완료. permId={}, permCode={}", created.permId(), created.permCode());
        return ResponseEntity.ok(ApiResponse.success(created));
    }

    /**
     * 권한 정보를 수정합니다.
     *
     * @param permId 수정 대상 권한 PK
     * @param request 수정 요청 본문
     * @return 수정된 권한 정보
     */
    @PutMapping("/{permId}")
    public ResponseEntity<ApiResponse<PermissionInfoResponse>> update(
            @PathVariable final long permId,
            @Valid @RequestBody final PermissionUpsertRequest request
    ) {
        log.info("권한 수정 요청. permId={}, permCode={}, resource={}, httpMethod={}",
                permId, request.permCode(), request.resource(), request.httpMethod());

        final var optionalUpdated = permissionAdminFacade.update(permId, request);
        if (optionalUpdated.isEmpty()) {
            log.warn("권한 수정 대상 없음. permId={}", permId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "수정할 권한을 찾을 수 없습니다."));
        }
        log.info("권한 수정 완료. permId={}, permCode={}", optionalUpdated.get().permId(), optionalUpdated.get().permCode());
        return ResponseEntity.ok(ApiResponse.success(optionalUpdated.get()));
    }

    /**
     * 권한을 삭제합니다.
     *
     * @param permId 삭제 대상 권한 PK
     * @return 삭제 성공 응답
     */
    @DeleteMapping("/{permId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable final long permId
    ) {
        log.info("권한 삭제 요청. permId={}", permId);
        permissionAdminFacade.delete(permId);
        log.info("권한 삭제 완료. permId={}", permId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
