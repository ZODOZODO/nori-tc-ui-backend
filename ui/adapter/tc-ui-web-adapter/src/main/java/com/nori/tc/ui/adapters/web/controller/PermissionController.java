package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.user.upsert.UpsertTcUiPermission;
import com.nori.tc.db.domain.user.TcUiPermission;
import com.nori.tc.ui.adapters.web.controller.support.UiPageRequestSupport;
import com.nori.tc.ui.adapters.web.dto.request.PermissionUpsertRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.PermissionInfoResponse;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.port.db.PermissionCrudPort;
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

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

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

    private final PermissionCrudPort permissionCrudPort;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * @param permissionCrudPort 권한 CRUD 포트
     */
    public PermissionController(final PermissionCrudPort permissionCrudPort) {
        this.permissionCrudPort = Objects.requireNonNull(permissionCrudPort, "permissionCrudPort is null");
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
        final PageRequest pageRequest = UiPageRequestSupport.resolve(offset, limit);

        if (log.isDebugEnabled()) {
            log.debug("권한 목록 조회 요청. offset={}, limit={}", pageRequest.offset(), pageRequest.limit());
        }

        final PagedResponse<TcUiPermission> page = permissionCrudPort.findAll(pageRequest);
        final PagedResponse<PermissionInfoResponse> responsePage = toPermissionPage(page);

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

        final Optional<TcUiPermission> optionalPermission = permissionCrudPort.findByPermId(permId);
        if (optionalPermission.isEmpty()) {
            log.warn("권한 단건 조회 결과 없음. permId={}", permId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "권한을 찾을 수 없습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(toPermissionInfoResponse(optionalPermission.get())));
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

        final UpsertTcUiPermission command = buildPermissionCommand(null, request);
        final TcUiPermission created = permissionCrudPort.upsert(command);

        log.info("권한 등록 완료. permId={}, permCode={}", created.permId(), created.permCode());
        return ResponseEntity.ok(ApiResponse.success(toPermissionInfoResponse(created)));
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

        if (permissionCrudPort.findByPermId(permId).isEmpty()) {
            log.warn("권한 수정 대상 없음. permId={}", permId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "수정할 권한을 찾을 수 없습니다."));
        }

        final UpsertTcUiPermission command = buildPermissionCommand(permId, request);
        final TcUiPermission updated = permissionCrudPort.upsert(command);

        log.info("권한 수정 완료. permId={}, permCode={}", updated.permId(), updated.permCode());
        return ResponseEntity.ok(ApiResponse.success(toPermissionInfoResponse(updated)));
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
        permissionCrudPort.deleteByPermId(permId);
        log.info("권한 삭제 완료. permId={}", permId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * 권한 upsert 명령을 생성합니다.
     *
     * @param permId 권한 PK(신규 생성 시 null)
     * @param request 원본 요청
     * @return upsert 명령
     */
    private static UpsertTcUiPermission buildPermissionCommand(
            final Long permId,
            final PermissionUpsertRequest request
    ) {
        return new UpsertTcUiPermission(
                permId,
                request.permCode(),
                request.permName(),
                request.resourceType(),
                request.matchType(),
                request.resource(),
                normalizeHttpMethod(request.httpMethod()),
                request.description(),
                resolveIsActive(request.isActive()),
                request.createdBy(),
                request.updatedBy()
        );
    }

    /**
     * HTTP 메서드 문자열을 표준 대문자로 정규화합니다.
     *
     * @param httpMethod 원본 입력값
     * @return null 또는 대문자 메서드 문자열
     */
    private static String normalizeHttpMethod(final String httpMethod) {
        if (httpMethod == null || httpMethod.isBlank()) {
            return null;
        }
        return httpMethod.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 권한 활성 여부 입력값을 기본값 정책에 따라 정규화합니다.
     *
     * @param isActive 요청 입력값
     * @return null이면 true, 아니면 요청값
     */
    private static boolean resolveIsActive(final Boolean isActive) {
        return (isActive == null) || isActive;
    }

    /**
     * 도메인 페이지 응답을 API 응답 DTO 페이지로 변환합니다.
     *
     * @param page 도메인 페이지 응답
     * @return API 응답 페이지
     */
    private static PagedResponse<PermissionInfoResponse> toPermissionPage(
            final PagedResponse<TcUiPermission> page
    ) {
        final List<PermissionInfoResponse> items = page.items().stream()
                .map(PermissionController::toPermissionInfoResponse)
                .toList();
        return PagedResponse.of(items, page.offset(), page.limit(), page.count());
    }

    /**
     * 권한 도메인을 응답 DTO로 변환합니다.
     *
     * @param permission 도메인 권한 객체
     * @return 응답 DTO
     */
    private static PermissionInfoResponse toPermissionInfoResponse(final TcUiPermission permission) {
        return new PermissionInfoResponse(
                permission.permId(),
                permission.permCode(),
                permission.permName(),
                permission.resourceType(),
                permission.matchType(),
                permission.resource(),
                permission.httpMethod(),
                permission.description(),
                permission.isActive(),
                permission.createdAt(),
                permission.updatedAt(),
                permission.createdBy(),
                permission.updatedBy()
        );
    }
}

