package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.core.commlog.CommLogEntry;
import com.nori.tc.ui.core.service.CommLogQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * 설비별 통신 로그 조회 REST API 컨트롤러입니다.
 */
@RestController
@RequestMapping("/api/comm-logs")
public class CommLogController {

    private final CommLogQueryService commLogQueryService;

    public CommLogController(final CommLogQueryService commLogQueryService) {
        this.commLogQueryService = Objects.requireNonNull(commLogQueryService, "commLogQueryService is null");
    }

    /**
     * 설비 통신 로그를 조회합니다.
     *
     * @param eqpId 설비 식별자
     * @param type  로그 유형 (eqp: 설비 통신, mes: MES 통신, all: 전체). 기본값: all
     */
    @GetMapping("/{eqpId}")
    public ResponseEntity<ApiResponse<List<CommLogEntry>>> getCommLogs(
            @PathVariable final String eqpId,
            @RequestParam(defaultValue = "all") final String type
    ) {
        final List<CommLogEntry> logs = switch (type) {
            case "eqp" -> commLogQueryService.getEqpLogs(eqpId);
            case "mes" -> commLogQueryService.getMesLogs(eqpId);
            default -> commLogQueryService.getAllLogs(eqpId);
        };
        return ResponseEntity.ok(ApiResponse.success(logs));
    }
}
