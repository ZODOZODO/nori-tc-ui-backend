package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.dto.request.DeployScenarioAdminActionRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployScenarioCatalogResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployScenarioReloadResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployScenarioValidationResponse;
import com.nori.tc.ui.core.deploy.DeployScenarioCatalog;
import com.nori.tc.ui.core.deploy.DeployScenarioDefinition;
import com.nori.tc.ui.core.deploy.DeployScenarioReloadResult;
import com.nori.tc.ui.core.deploy.DeployScenarioValidationResult;
import com.nori.tc.ui.core.service.DeployScenarioCatalogService;
import com.nori.tc.ui.domain.auth.UserPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * deploy scenario catalog 운영 API 입니다.
 */
@RestController
@RequestMapping("/api/admin/deploy-scenarios")
public class DeployScenarioAdminController {

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String DEFAULT_ACTOR = "SYSTEM";

    private final DeployScenarioCatalogService deployScenarioCatalogService;

    public DeployScenarioAdminController(final DeployScenarioCatalogService deployScenarioCatalogService) {
        this.deployScenarioCatalogService = Objects.requireNonNull(
                deployScenarioCatalogService,
                "deployScenarioCatalogService is null"
        );
    }

    /**
     * 현재 scenario source를 검증합니다.
     *
     * @param request 요청 본문
     * @param authentication 현재 인증 정보
     * @return validate 결과
     */
    @PostMapping("/validate")
    public ResponseEntity<ApiResponse<DeployScenarioValidationResponse>> validate(
            @RequestBody(required = false) final DeployScenarioAdminActionRequest request,
            final Authentication authentication
    ) {
        final DeployScenarioValidationResult result = deployScenarioCatalogService.validate(
                request == null ? null : request.reason(),
                resolveActor(authentication)
        );

        return ResponseEntity.ok(ApiResponse.success(new DeployScenarioValidationResponse(
                result.success() ? STATUS_SUCCESS : STATUS_FAILED,
                result.processedAt(),
                result.loadedScenarioCount(),
                result.validationErrors()
        )));
    }

    /**
     * scenario catalog를 재적재합니다.
     *
     * @param request 요청 본문
     * @param authentication 현재 인증 정보
     * @return reload 결과
     */
    @PostMapping("/reload")
    public ResponseEntity<ApiResponse<DeployScenarioReloadResponse>> reload(
            @RequestBody(required = false) final DeployScenarioAdminActionRequest request,
            final Authentication authentication
    ) {
        final DeployScenarioReloadResult result = deployScenarioCatalogService.reload(
                request == null ? null : request.reason(),
                resolveActor(authentication)
        );

        return ResponseEntity.ok(ApiResponse.success(new DeployScenarioReloadResponse(
                result.success() ? STATUS_SUCCESS : STATUS_FAILED,
                result.catalogVersionBefore(),
                result.catalogVersionAfter(),
                result.loadedScenarioCount(),
                result.reloadedAt(),
                result.validationErrors()
        )));
    }

    /**
     * 현재 적재된 scenario catalog를 조회합니다.
     *
     * @return catalog 응답
     */
    @GetMapping("/catalog")
    public ResponseEntity<ApiResponse<DeployScenarioCatalogResponse>> catalog() {
        final DeployScenarioCatalog catalog = deployScenarioCatalogService.currentCatalog();
        final List<DeployScenarioCatalogResponse.ScenarioSummary> scenarios = catalog.sortedScenarios().stream()
                .map(DeployScenarioAdminController::toScenarioSummary)
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
