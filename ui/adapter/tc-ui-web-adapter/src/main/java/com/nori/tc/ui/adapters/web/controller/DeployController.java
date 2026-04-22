package com.nori.tc.ui.adapters.web.controller;

import tools.jackson.databind.ObjectMapper;
import com.nori.tc.ui.adapters.web.controller.support.UiPageRequestSupport;
import com.nori.tc.ui.adapters.web.dto.request.DeployCreateRequest;
import com.nori.tc.ui.adapters.web.dto.request.DeployRegisterScenarioPreviewRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployActionResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployCreateResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployHistoryPageResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployRegisterScenarioPreviewResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployRegisterTargetPageResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployScenarioCatalogResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeploySummaryResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployTargetPageResponse;
import com.nori.tc.ui.core.deploy.DeployCreateResult;
import com.nori.tc.ui.core.deploy.DeployActionResult;
import com.nori.tc.ui.core.deploy.DeployDeleteResult;
import com.nori.tc.ui.core.deploy.DeployHistoryView;
import com.nori.tc.ui.core.deploy.DeployRegisterScenarioPreviewResult;
import com.nori.tc.ui.core.deploy.DeploySummaryView;
import com.nori.tc.ui.core.deploy.DeployTargetPage;
import com.nori.tc.ui.core.deploy.DeployRegisterTargetView;
import com.nori.tc.ui.core.deploy.DeployScenarioCatalog;
import com.nori.tc.ui.core.service.DeployPlanner;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.service.DeployRegisterQueryService;
import com.nori.tc.ui.core.service.DeployService;
import com.nori.tc.ui.core.service.DeployScenarioCatalogService;
import com.nori.tc.ui.domain.auth.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * deploy 생성 / polling API 입니다.
 */
@RestController
@RequestMapping("/api/deploys")
public class DeployController {

    private static final int DEFAULT_TARGET_PAGE_SIZE = 50;
    private static final String DEFAULT_ACTOR = "SYSTEM";

    private final DeployService deployService;
    private final DeployPlanner deployPlanner;
    private final DeployRegisterQueryService deployRegisterQueryService;
    private final DeployScenarioCatalogService deployScenarioCatalogService;
    private final DeployControllerRequestMapper requestMapper;
    private final DeployControllerResponseMapper responseMapper;

    public DeployController(
            final DeployService deployService,
            final DeployPlanner deployPlanner,
            final DeployRegisterQueryService deployRegisterQueryService,
            final DeployScenarioCatalogService deployScenarioCatalogService,
            final ObjectMapper objectMapper
    ) {
        this.deployService = Objects.requireNonNull(deployService, "deployService is null");
        this.deployPlanner = Objects.requireNonNull(deployPlanner, "deployPlanner is null");
        this.deployRegisterQueryService = Objects.requireNonNull(
                deployRegisterQueryService,
                "deployRegisterQueryService is null"
        );
        this.deployScenarioCatalogService = Objects.requireNonNull(
                deployScenarioCatalogService,
                "deployScenarioCatalogService is null"
        );
        this.requestMapper = new DeployControllerRequestMapper(
                Objects.requireNonNull(objectMapper, "objectMapper is null")
        );
        this.responseMapper = new DeployControllerResponseMapper();
    }

    /**
     * Deploy Register 대상 설비 목록을 조회합니다.
     *
     * @param modelName model name 필터
     * @param modelVersionKey model version key 필터
     * @param eqpId eqp id 부분 검색 필터
     * @param offset 페이지 시작 위치
     * @param limit 페이지 크기
     * @return 대상 설비 페이지 응답
     */
    @GetMapping("/register/targets")
    public ResponseEntity<ApiResponse<DeployRegisterTargetPageResponse>> getRegisterTargets(
            @RequestParam(name = "modelName", required = false) final String modelName,
            @RequestParam(name = "modelVersionKey", required = false) final Long modelVersionKey,
            @RequestParam(name = "eqpId", required = false) final String eqpId,
            @RequestParam(name = "offset", required = false) final Integer offset,
            @RequestParam(name = "limit", required = false) final Integer limit
    ) {
        final UiPageRequest pageRequest = UiPageRequestSupport.resolve(offset, limit);
        final PagedResponse<DeployRegisterTargetView> page = deployRegisterQueryService.findRegisterTargets(
                modelName,
                modelVersionKey,
                eqpId,
                pageRequest
        );

        return ResponseEntity.ok(ApiResponse.success(responseMapper.toRegisterTargetPageResponse(page)));
    }

    /**
     * deploy 목록을 조회합니다.
     *
     * @param modelName deploy name / model name 필터
     * @param requestedBy 요청 사용자 필터
     * @param fromDate 생성일 시작 inclusive
     * @param toDate 생성일 종료 inclusive
     * @param terminalOnly 완료 상태만 조회할지 여부
     * @param offset 페이지 시작 위치
     * @param limit 페이지 크기
     * @return deploy history 페이지 응답
     */
    @GetMapping
    public ResponseEntity<ApiResponse<DeployHistoryPageResponse>> getHistory(
            @RequestParam(name = "modelName", required = false) final String modelName,
            @RequestParam(name = "requestedBy", required = false) final String requestedBy,
            @RequestParam(name = "fromDate", required = false) final String fromDate,
            @RequestParam(name = "toDate", required = false) final String toDate,
            @RequestParam(name = "terminalOnly", required = false) final Boolean terminalOnly,
            @RequestParam(name = "offset", required = false) final Integer offset,
            @RequestParam(name = "limit", required = false) final Integer limit
    ) {
        final UiPageRequest pageRequest = UiPageRequestSupport.resolve(offset, limit);
        final LocalDate fromLocalDate = requestMapper.parseLocalDate(fromDate, "fromDate");
        final LocalDate toLocalDate = requestMapper.parseLocalDate(toDate, "toDate");
        if (fromLocalDate != null && toLocalDate != null && toLocalDate.isBefore(fromLocalDate)) {
            throw new UiBadRequestException("toDate는 fromDate보다 빠를 수 없습니다.");
        }

        final PagedResponse<DeployHistoryView> page = deployService.getDeployHistory(
                modelName,
                requestedBy,
                fromLocalDate == null ? null : fromLocalDate.atStartOfDay().atOffset(ZoneOffset.UTC),
                toLocalDate == null ? null : toLocalDate.plusDays(1L).atStartOfDay().atOffset(ZoneOffset.UTC),
                Boolean.TRUE.equals(terminalOnly),
                pageRequest
        );

        return ResponseEntity.ok(ApiResponse.success(responseMapper.toHistoryPageResponse(page)));
    }

    /**
     * deploy를 생성하고 실행 대기 상태로 반환합니다.
     *
     * @param request 생성 요청
     * @param authentication 현재 인증 정보
     * @return 202 Accepted + deployKey 응답
     */
    @PostMapping
    public ResponseEntity<ApiResponse<DeployCreateResponse>> create(
            @Valid @RequestBody final DeployCreateRequest request,
            final Authentication authentication
    ) {
        final DeployCreateResult result = deployService.createDeploy(
                requestMapper.toCommand(request),
                resolveActor(authentication)
        );

        return ResponseEntity.accepted().body(ApiResponse.success(responseMapper.toCreateResponse(result)));
    }

    /**
     * Register deploy 시나리오 preview 를 계산합니다.
     *
     * @param request preview 요청
     * @return 권장 시나리오와 선택 가능 목록
     */
    @PostMapping("/register/scenario-preview")
    public ResponseEntity<ApiResponse<DeployRegisterScenarioPreviewResponse>> previewRegisterScenario(
            @Valid @RequestBody final DeployRegisterScenarioPreviewRequest request
    ) {
        final DeployRegisterScenarioPreviewResult result = deployPlanner.previewRegisterScenarioSelection(
                requestMapper.toPreviewCommand(request)
        );
        return ResponseEntity.ok(ApiResponse.success(responseMapper.toRegisterScenarioPreviewResponse(result)));
    }

    /**
     * deploy 이벤트 타입 기준으로 사용 가능한 scenario 목록을 조회합니다.
     *
     * @param eventType 이벤트 타입
     * @return scenario 응답
     */
    @GetMapping("/scenarios")
    public ResponseEntity<ApiResponse<DeployScenarioCatalogResponse>> getScenarios(
            @RequestParam(name = "eventType", required = false) final String eventType
    ) {
        final UiCommandEventType eventTypeEnum = requestMapper.parseEventType(eventType);
        final DeployScenarioCatalog catalog = deployScenarioCatalogService.currentCatalog();
        final List<DeployScenarioCatalogResponse.ScenarioSummary> scenarios = catalog.sortedScenarios().stream()
                .filter(definition -> eventTypeEnum == null
                        || definition.appliesTo().eventTypes().contains(eventTypeEnum))
                .map(responseMapper::toScenarioSummary)
                .toList();

        return ResponseEntity.ok(ApiResponse.success(new DeployScenarioCatalogResponse(
                catalog.catalogVersion(),
                catalog.catalogChecksum(),
                catalog.loadedAt(),
                catalog.loadedBy(),
                catalog.reloadReason(),
                catalog.scenarioCount(),
                scenarios
        )));
    }

    /**
     * 진행 중 deploy 취소를 요청합니다.
     *
     * @param deployKey deploy key
     * @param authentication 현재 인증 정보
     * @return 취소 결과 응답
     */
    @PostMapping("/{deployKey}/cancel")
    public ResponseEntity<ApiResponse<DeployActionResponse>> cancel(
            @PathVariable final long deployKey,
            final Authentication authentication
    ) {
        final DeployActionResult result = deployService.cancelDeploy(deployKey, resolveActor(authentication));
        return ResponseEntity.ok(ApiResponse.success(responseMapper.toActionResponse(result)));
    }

    /**
     * READY deploy 를 실행합니다.
     *
     * @param deployKey deploy key
     * @param authentication 현재 인증 정보
     * @return 실행 시작 결과 응답
     */
    @PostMapping("/{deployKey}/start")
    public ResponseEntity<ApiResponse<DeployActionResponse>> start(
            @PathVariable final long deployKey,
            final Authentication authentication
    ) {
        final DeployActionResult result = deployService.startDeploy(deployKey, resolveActor(authentication));
        return ResponseEntity.ok(ApiResponse.success(responseMapper.toActionResponse(result)));
    }

    /**
     * RUNNING deploy 에 safe hold 를 요청합니다.
     *
     * @param deployKey deploy key
     * @param authentication 현재 인증 정보
     * @return hold 요청 결과 응답
     */
    @PostMapping("/{deployKey}/hold")
    public ResponseEntity<ApiResponse<DeployActionResponse>> hold(
            @PathVariable final long deployKey,
            final Authentication authentication
    ) {
        final DeployActionResult result = deployService.holdDeploy(deployKey, resolveActor(authentication));
        return ResponseEntity.ok(ApiResponse.success(responseMapper.toActionResponse(result)));
    }

    /**
     * 종료된 deploy 를 기준으로 새 대기 deploy 를 다시 생성합니다.
     *
     * @param deployKey 원본 deploy key
     * @param authentication 현재 인증 정보
     * @return 새 deploy 생성 응답
     */
    @PostMapping("/{deployKey}/redeploy")
    public ResponseEntity<ApiResponse<DeployCreateResponse>> redeploy(
            @PathVariable final long deployKey,
            final Authentication authentication
    ) {
        final DeployCreateResult result = deployService.redeploy(deployKey, resolveActor(authentication));
        return ResponseEntity.accepted().body(ApiResponse.success(responseMapper.toCreateResponse(result)));
    }

    /**
     * 종료된 deploy 이력을 삭제합니다.
     *
     * @param deployKey deploy key
     * @param authentication 현재 인증 정보
     * @return 삭제 결과 응답
     */
    @DeleteMapping("/{deployKey}")
    public ResponseEntity<ApiResponse<DeployActionResponse>> delete(
            @PathVariable final long deployKey,
            final Authentication authentication
    ) {
        final DeployDeleteResult result = deployService.deleteDeploy(deployKey, resolveActor(authentication));
        return ResponseEntity.ok(ApiResponse.success(responseMapper.toDeleteActionResponse(result)));
    }

    /**
     * deploy summary polling 결과를 조회합니다.
     *
     * @param deployKey deploy key
     * @return summary 응답
     */
    @GetMapping("/{deployKey}")
    public ResponseEntity<ApiResponse<DeploySummaryResponse>> getSummary(
            @PathVariable final long deployKey
    ) {
        final DeploySummaryView summary = deployService.getDeploySummary(deployKey);
        return ResponseEntity.ok(ApiResponse.success(responseMapper.toSummaryResponse(summary)));
    }

    /**
     * deploy target 목록을 조회합니다.
     *
     * @param deployKey deploy key
     * @param page page index
     * @param size page size
     * @param statusFilter 상태 필터
     * @param eqpIdFilter eqpId 필터
     * @return target 목록 응답
     */
    @GetMapping("/{deployKey}/targets")
    public ResponseEntity<ApiResponse<DeployTargetPageResponse>> getTargets(
            @PathVariable final long deployKey,
            @RequestParam(name = "page", required = false) final Integer page,
            @RequestParam(name = "size", required = false) final Integer size,
            @RequestParam(name = "status", required = false) final String statusFilter,
            @RequestParam(name = "eqpId", required = false) final String eqpIdFilter
    ) {
        final DeployTargetPage targetPage = deployService.getDeployTargets(
                deployKey,
                page == null ? 0 : page,
                size == null ? DEFAULT_TARGET_PAGE_SIZE : size,
                statusFilter,
                eqpIdFilter
        );
        return ResponseEntity.ok(ApiResponse.success(responseMapper.toTargetPageResponse(targetPage)));
    }

    private static String resolveActor(final Authentication authentication) {
        if (authentication == null) {
            return DEFAULT_ACTOR;
        }
        if (authentication.getPrincipal() instanceof UserPrincipal principal
                && principal.userId() != null
                && !principal.userId().isBlank()) {
            return principal.userId().trim();
        }
        return DEFAULT_ACTOR;
    }
}
