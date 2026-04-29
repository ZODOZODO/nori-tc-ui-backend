package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.config.UiDualRequestProperties;
import com.nori.tc.ui.adapters.web.config.UiInternalApiProperties;
import com.nori.tc.ui.adapters.web.controller.support.EqpControllerResponseSupport;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.AsyncAcceptResponse;
import com.nori.tc.ui.adapters.web.dto.response.EqpManageDetailResponse;
import com.nori.tc.ui.core.eqp.EqpAsyncAcceptResult;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.service.EqpManagementService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * demo-tester 전용 내부 API입니다.
 *
 * <p>조회: GET /eqp/{eqpId}/manage
 * <p>설비 lifecycle 트리거: POST /eqp/{eqpId}/start, /eqp/{eqpId}/end
 * <p>모든 엔드포인트는 internal-header 인증을 요구합니다.
 */
@RestController
@RequestMapping("/api/internal/demo")
public class DemoInternalController {

    private final EqpManagementService eqpManagementService;
    private final UiInternalApiProperties internalApiProperties;
    private final UiDualRequestProperties dualRequestProperties;

    public DemoInternalController(
            final EqpManagementService eqpManagementService,
            final UiInternalApiProperties internalApiProperties,
            final UiDualRequestProperties dualRequestProperties
    ) {
        this.eqpManagementService = Objects.requireNonNull(eqpManagementService, "eqpManagementService is null");
        this.internalApiProperties = Objects.requireNonNull(internalApiProperties, "internalApiProperties is null");
        this.dualRequestProperties = Objects.requireNonNull(dualRequestProperties, "dualRequestProperties is null");
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

    @PostMapping("/eqp/{eqpId}/start")
    public ResponseEntity<ApiResponse<AsyncAcceptResponse>> startEqpForDemoTester(
            @PathVariable final String eqpId,
            @RequestParam(name = "interfaceType", defaultValue = "SOCKET") final String interfaceType,
            final HttpServletRequest request
    ) {
        validateInternalHeader(request);
        final EqpAsyncAcceptResult acceptResult = eqpManagementService.startAsync(
                eqpId,
                interfaceType,
                null,
                dualRequestProperties.getDualRequestTimeoutMs()
        );
        return toAsyncAcceptResponse(acceptResult);
    }

    @PostMapping("/eqp/{eqpId}/end")
    public ResponseEntity<ApiResponse<AsyncAcceptResponse>> endEqpForDemoTester(
            @PathVariable final String eqpId,
            @RequestParam(name = "interfaceType", defaultValue = "SOCKET") final String interfaceType,
            final HttpServletRequest request
    ) {
        validateInternalHeader(request);
        final EqpAsyncAcceptResult acceptResult = eqpManagementService.endAsync(
                eqpId,
                interfaceType,
                null,
                dualRequestProperties.getDualRequestTimeoutMs()
        );
        return toAsyncAcceptResponse(acceptResult);
    }

    private static ResponseEntity<ApiResponse<AsyncAcceptResponse>> toAsyncAcceptResponse(
            final EqpAsyncAcceptResult acceptResult
    ) {
        if (acceptResult.accepted()) {
            return ResponseEntity.accepted().body(ApiResponse.success(new AsyncAcceptResponse(acceptResult.traceId())));
        }
        return ResponseEntity.status(acceptResult.statusCode())
                .body(ApiResponse.error(acceptResult.errorCode(), acceptResult.errorMessage()));
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
        if (!matchesSecret(expectedHeaderValue, actualHeaderValue)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "내부 API 인증에 실패했습니다.");
        }
    }

    private static boolean matchesSecret(final String expectedHeaderValue, final String actualHeaderValue) {
        if (actualHeaderValue == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedHeaderValue.getBytes(StandardCharsets.UTF_8),
                actualHeaderValue.getBytes(StandardCharsets.UTF_8)
        );
    }
}
