package com.nori.tc.ui.adapters.web.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nori.tc.ui.adapters.web.dto.request.DeployCreateRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployCreateResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployResultMessageResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeploySummaryResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployTargetPageResponse;
import com.nori.tc.ui.core.deploy.DeployCreateResult;
import com.nori.tc.ui.core.deploy.DeployExecutionPolicy;
import com.nori.tc.ui.core.deploy.DeployPlanCommand;
import com.nori.tc.ui.core.deploy.DeployResultMessage;
import com.nori.tc.ui.core.deploy.DeploySummaryView;
import com.nori.tc.ui.core.deploy.DeployTargetCounts;
import com.nori.tc.ui.core.deploy.DeployTargetPage;
import com.nori.tc.ui.core.deploy.DeployTargetSnapshot;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.service.DeployService;
import com.nori.tc.ui.domain.auth.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
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
    private final ObjectMapper objectMapper;

    public DeployController(
            final DeployService deployService,
            final ObjectMapper objectMapper
    ) {
        this.deployService = Objects.requireNonNull(deployService, "deployService is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
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
