package com.nori.tc.ui.adapters.web.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nori.tc.ui.adapters.web.controller.support.UiPageRequestSupport;
import com.nori.tc.ui.adapters.web.dto.request.DeployCreateRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployActionResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployCreateResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployHistoryPageResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployRegisterTargetPageResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployResultMessageResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployScenarioCatalogResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeploySummaryResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployTargetPageResponse;
import com.nori.tc.ui.core.deploy.DeployCreateResult;
import com.nori.tc.ui.core.deploy.DeployActionResult;
import com.nori.tc.ui.core.deploy.DeployDeleteResult;
import com.nori.tc.ui.core.deploy.DeployExecutionPolicy;
import com.nori.tc.ui.core.deploy.DeployHistoryView;
import com.nori.tc.ui.core.deploy.DeployPlanCommand;
import com.nori.tc.ui.core.deploy.DeployResultMessage;
import com.nori.tc.ui.core.deploy.DeployScenarioDefinition;
import com.nori.tc.ui.core.deploy.DeploySummaryView;
import com.nori.tc.ui.core.deploy.DeployTargetCounts;
import com.nori.tc.ui.core.deploy.DeployTargetPage;
import com.nori.tc.ui.core.deploy.DeployRegisterTargetView;
import com.nori.tc.ui.core.deploy.DeployTargetSnapshot;
import com.nori.tc.ui.core.deploy.DeployScenarioCatalog;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
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
    private final DeployRegisterQueryService deployRegisterQueryService;
    private final DeployScenarioCatalogService deployScenarioCatalogService;
    private final ObjectMapper objectMapper;

    public DeployController(
            final DeployService deployService,
            final DeployRegisterQueryService deployRegisterQueryService,
            final DeployScenarioCatalogService deployScenarioCatalogService,
            final ObjectMapper objectMapper
    ) {
        this.deployService = Objects.requireNonNull(deployService, "deployService is null");
        this.deployRegisterQueryService = Objects.requireNonNull(
                deployRegisterQueryService,
                "deployRegisterQueryService is null"
        );
        this.deployScenarioCatalogService = Objects.requireNonNull(
                deployScenarioCatalogService,
                "deployScenarioCatalogService is null"
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
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

        return ResponseEntity.ok(ApiResponse.success(toRegisterTargetPageResponse(page)));
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
        final LocalDate fromLocalDate = parseLocalDate(fromDate, "fromDate");
        final LocalDate toLocalDate = parseLocalDate(toDate, "toDate");
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

        return ResponseEntity.ok(ApiResponse.success(toHistoryPageResponse(page)));
    }

    /**
     * deploy를 생성하고 background execution을 시작합니다.
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
                toCommand(request),
                resolveActor(authentication)
        );

        final long deployKey = result.deployKey();
        return ResponseEntity.accepted().body(ApiResponse.success(new DeployCreateResponse(
                deployKey,
                result.deployStatus().name(),
                new DeployCreateResponse.PollingResponse(
                        "/api/deploys/" + deployKey,
                        "/api/deploys/" + deployKey + "/targets"
                ),
                result.resultCode(),
                toResultMessage(result.resultMessage())
        )));
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
        final UiCommandEventType eventTypeEnum = parseEventType(eventType);
        final DeployScenarioCatalog catalog = deployScenarioCatalogService.currentCatalog();
        final List<DeployScenarioCatalogResponse.ScenarioSummary> scenarios = catalog.sortedScenarios().stream()
                .filter(definition -> eventTypeEnum == null
                        || definition.appliesTo().eventTypes().contains(eventTypeEnum))
                .map(DeployController::toScenarioSummary)
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
        return ResponseEntity.ok(ApiResponse.success(new DeployActionResponse(
                result.deployKey(),
                result.deployStatus().name(),
                null,
                result.resultCode(),
                toResultMessage(result.resultMessage())
        )));
    }

    /**
     * 종료된 deploy 를 기준으로 새 deploy 를 다시 생성합니다.
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
        final long nextDeployKey = result.deployKey();
        return ResponseEntity.accepted().body(ApiResponse.success(new DeployCreateResponse(
                nextDeployKey,
                result.deployStatus().name(),
                new DeployCreateResponse.PollingResponse(
                        "/api/deploys/" + nextDeployKey,
                        "/api/deploys/" + nextDeployKey + "/targets"
                ),
                result.resultCode(),
                toResultMessage(result.resultMessage())
        )));
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
        return ResponseEntity.ok(ApiResponse.success(new DeployActionResponse(
                result.deployKey(),
                null,
                result.deleted(),
                result.resultCode(),
                toResultMessage(result.resultMessage())
        )));
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
        return ResponseEntity.ok(ApiResponse.success(toSummaryResponse(summary)));
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
        return ResponseEntity.ok(ApiResponse.success(toTargetPageResponse(targetPage)));
    }

    private DeployPlanCommand toCommand(final DeployCreateRequest request) {
        try {
            return new DeployPlanCommand(
                    request.deployName(),
                    UiCommandEventType.fromText(request.eventType()),
                    DeployExecutionPolicy.fromText(request.executionPolicy()),
                    new DeployPlanCommand.CommonRequest(
                            request.commonRequest().interfaceType(),
                            toJsonObject(request.commonRequest().uiMessage(), "commonRequest.uiMessage")
                    ),
                    request.targets().stream()
                            .map(target -> new DeployPlanCommand.TargetRequest(
                                    target.eqpId(),
                                    toJsonObject(target.uiMessageOverride(), "targets[].uiMessageOverride")
                            ))
                            .toList()
            );
        } catch (IllegalArgumentException exception) {
            throw new UiBadRequestException(exception.getMessage());
        }
    }

    private DeploySummaryResponse toSummaryResponse(final DeploySummaryView summary) {
        return new DeploySummaryResponse(
                summary.deployKey(),
                summary.deployName(),
                summary.eventType().name(),
                summary.deployStatus().name(),
                summary.executionPolicy().name(),
                toIso(summary.createdAt()),
                toIso(summary.startedAt()),
                toIso(summary.finishedAt()),
                toTargetSummaryResponse(summary.targetSummary()),
                summary.terminal(),
                summary.popupRequired(),
                summary.resultCode(),
                toResultMessage(summary.resultMessage())
        );
    }

    private DeploySummaryResponse.TargetSummaryResponse toTargetSummaryResponse(final DeployTargetCounts counts) {
        return new DeploySummaryResponse.TargetSummaryResponse(
                counts.total(),
                counts.pending(),
                counts.noChange(),
                counts.running(),
                counts.completed(),
                counts.failed(),
                counts.rollingBack(),
                counts.rolledBack(),
                counts.compensationFailed(),
                counts.canceled()
        );
    }

    private DeployTargetPageResponse toTargetPageResponse(final DeployTargetPage page) {
        final List<DeployTargetPageResponse.DeployTargetResponse> targets = page.targets().stream()
                .map(this::toTargetResponse)
                .toList();

        return new DeployTargetPageResponse(
                page.deployKey(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages(),
                targets
        );
    }

    private DeployTargetPageResponse.DeployTargetResponse toTargetResponse(final DeployTargetSnapshot target) {
        return new DeployTargetPageResponse.DeployTargetResponse(
                target.deployTargetKey(),
                target.eqpId(),
                target.targetStatus().name(),
                target.currentStepOrder(),
                target.currentStepCode(),
                target.currentStepStatus() == null ? null : target.currentStepStatus().name(),
                target.rollbackRequired(),
                target.lastErrorCode(),
                target.lastErrorMessage(),
                toIso(target.updatedAt())
        );
    }

    private DeployRegisterTargetPageResponse toRegisterTargetPageResponse(
            final PagedResponse<DeployRegisterTargetView> page
    ) {
        return new DeployRegisterTargetPageResponse(
                page.items().stream()
                        .map(this::toRegisterTargetResponse)
                        .toList(),
                page.offset(),
                page.limit(),
                page.count()
        );
    }

    private DeployHistoryPageResponse toHistoryPageResponse(final PagedResponse<DeployHistoryView> page) {
        return new DeployHistoryPageResponse(
                page.items().stream()
                        .map(this::toHistoryResponse)
                        .toList(),
                page.offset(),
                page.limit(),
                page.count()
        );
    }

    private DeployRegisterTargetPageResponse.DeployRegisterTargetResponse toRegisterTargetResponse(
            final DeployRegisterTargetView item
    ) {
        return new DeployRegisterTargetPageResponse.DeployRegisterTargetResponse(
                item.eqpKey(),
                item.eqpId(),
                item.commInterface(),
                item.enabled(),
                item.modelVersionKey(),
                item.modelName(),
                item.modelVersion(),
                item.modelStatus(),
                item.businessJarRef(),
                item.businessJarFileName(),
                item.appliedParamVersion()
        );
    }

    private DeployHistoryPageResponse.DeployHistoryResponse toHistoryResponse(final DeployHistoryView item) {
        return new DeployHistoryPageResponse.DeployHistoryResponse(
                item.deployKey(),
                item.deployName(),
                item.eventType().name(),
                item.deployStatus().name(),
                item.executionPolicy().name(),
                item.requestedBy(),
                toIso(item.createdAt()),
                toIso(item.startedAt()),
                toIso(item.finishedAt()),
                item.terminal(),
                item.interfaceType(),
                item.selectedModelName(),
                item.selectedModelVersion(),
                item.selectedBusinessJarName(),
                toTargetSummaryResponse(item.targetSummary())
        );
    }

    private static DeployScenarioCatalogResponse.ScenarioSummary toScenarioSummary(
            final DeployScenarioDefinition definition
    ) {
        return new DeployScenarioCatalogResponse.ScenarioSummary(
                definition.scenario().code(),
                definition.scenario().name(),
                definition.scenario().summary(),
                definition.version(),
                definition.steps().size()
        );
    }

    private DeployResultMessageResponse toResultMessage(final DeployResultMessage resultMessage) {
        return new DeployResultMessageResponse(
                resultMessage.title(),
                resultMessage.message(),
                resultMessage.severity().name()
        );
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

    private static String toIso(final OffsetDateTime value) {
        return value == null ? null : value.toString();
    }

    private static LocalDate parseLocalDate(
            final String rawDate,
            final String fieldName
    ) {
        if (rawDate == null || rawDate.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(rawDate.trim());
        } catch (DateTimeParseException exception) {
            throw new UiBadRequestException(fieldName + "는 yyyy-MM-dd 형식이어야 합니다.");
        }
    }

    private static UiCommandEventType parseEventType(final String rawEventType) {
        if (rawEventType == null || rawEventType.isBlank()) {
            return null;
        }

        try {
            return UiCommandEventType.fromText(rawEventType);
        } catch (IllegalArgumentException exception) {
            throw new UiBadRequestException(exception.getMessage());
        }
    }

    private JsonNode toJsonObject(
            final Object value,
            final String fieldName
    ) {
        if (value == null) {
            return null;
        }
        final JsonNode jsonNode = objectMapper.valueToTree(value);
        if (!jsonNode.isObject()) {
            throw new UiBadRequestException(fieldName + " 는 JSON object 여야 합니다.");
        }
        return jsonNode;
    }
}
