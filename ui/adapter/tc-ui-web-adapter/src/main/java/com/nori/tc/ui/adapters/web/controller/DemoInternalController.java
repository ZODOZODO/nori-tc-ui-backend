package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.config.UiInternalApiProperties;
import com.nori.tc.ui.adapters.web.controller.support.EqpControllerResponseSupport;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.EqpManageDetailResponse;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.service.EqpManagementService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

/**
 * demo-tester 전용 내부 조회 API입니다.
 */
@RestController
@RequestMapping("/api/internal/demo")
public class DemoInternalController {

    private final EqpManagementService eqpManagementService;
    private final UiInternalApiProperties internalApiProperties;

    public DemoInternalController(
            final EqpManagementService eqpManagementService,
            final UiInternalApiProperties internalApiProperties
    ) {
        this.eqpManagementService = Objects.requireNonNull(eqpManagementService, "eqpManagementService is null");
        this.internalApiProperties = Objects.requireNonNull(internalApiProperties, "internalApiProperties is null");
    }

    @GetMapping("/eqp/{eqpId}/manage")
    public ResponseEntity<ApiResponse<EqpManageDetailResponse>> getManageDetailForDemoTester(
            @PathVariable final String eqpId,
            final HttpServletRequest request
    ) {
        validateInternalHeader(request);
        return eqpManagementService.getManageDetail(eqpId)
                .map(EqpControllerResponseSupport::toManageDetailResponse)
                .map(ApiResponse::success)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new UiNotFoundException("설비를 찾을 수 없습니다. eqpId=" + eqpId));
    }

    private void validateInternalHeader(final HttpServletRequest request) {
        final String expectedHeaderName = Objects.requireNonNull(
                internalApiProperties.getHeaderName(),
                "tc.ui.backend.internal-api.header-name is null"
        );
        final String expectedHeaderValue = Objects.requireNonNull(
                internalApiProperties.getHeaderValue(),
                "tc.ui.backend.internal-api.header-value is null"
        );
        if (expectedHeaderValue.isBlank()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "내부 API 토큰이 설정되지 않았습니다.");
        }
        final String actualHeaderValue = request.getHeader(expectedHeaderName);
        if (!expectedHeaderValue.equals(actualHeaderValue)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "내부 API 인증에 실패했습니다.");
        }
    }
}
