package com.nori.tc.ui.adapters.web.controller;

import java.util.List;
import java.util.Objects;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.LineResponse;
import com.nori.tc.ui.core.service.LineQueryService;

/**
 * 라인(tc_line) 조회 REST API 컨트롤러입니다.
 *
 * <p>제공 엔드포인트:</p>
 * <ul>
 *   <li>GET /api/line — 활성 라인 목록(line_code 오름차순). 상단바 라인 드롭다운 / eqp 폼 라인 옵션 공용.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/line")
public class LineController {

    private final LineQueryService lineQueryService;

    public LineController(final LineQueryService lineQueryService) {
        this.lineQueryService = Objects.requireNonNull(lineQueryService, "lineQueryService is null");
    }

    /**
     * 활성 라인 목록을 조회합니다.
     *
     * @return 활성 라인 목록 응답
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<LineResponse>>> list() {
        final List<LineResponse> lines = lineQueryService.getActiveLines().stream()
                .map(LineResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(lines));
    }
}
