package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.OperationStatusResponse;
import com.nori.tc.ui.core.coordination.UiOperationQueryService;
import com.nori.tc.ui.core.coordination.model.UiOperationStatusView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Optional;

/**
 * operation 조회 REST API 컨트롤러입니다(§13.3 / Plan §8 B1).
 *
 * <p>{@code GET /api/async/{id}} 를 범용 operation 조회로 확장합니다. {@code id} 는 operationId 와
 * 레거시 traceId 를 모두 받습니다 — URL 을 유지해 기존 Front 호출을 깨지 않기 위함입니다.</p>
 *
 * <p>응답 규칙</p>
 * <ul>
 *   <li>비terminal: {@code 202} + {@code status=PENDING} + 상세 phase</li>
 *   <li>terminal 성공/실패/복구필요: {@code 200} + {@code PASS}/{@code FAIL}/{@code REPAIR_REQUIRED}</li>
 *   <li>존재하지 않는 ID: {@code 404}</li>
 *   <li>조정 원장 조회 불가: {@code 503} — <b>404 로 환원하지 않습니다</b>. 환원하면 Front 가
 *       "작업이 사라졌다" 로 읽고 재요청해 중복 mutation 을 만듭니다.</li>
 * </ul>
 *
 * <p>처리 deadline 이 지나도 {@code 408}/{@code TIMEOUT} terminal 을 만들지 않습니다 — 지연은
 * {@code phase=RECONCILING} 으로만 표현되고, 늦게 도착한 적용이 실제 결과로 수렴합니다.</p>
 */
@RestController
@RequestMapping("/api/async")
public class AsyncResultController {

    private static final Logger log = LoggerFactory.getLogger(AsyncResultController.class);

    private final UiOperationQueryService operationQueryService;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * @param operationQueryService operation 조회 서비스
     */
    public AsyncResultController(final UiOperationQueryService operationQueryService) {
        this.operationQueryService = Objects.requireNonNull(operationQueryService, "operationQueryService is null");
    }

    /**
     * operationId 또는 traceId 로 작업 상태를 조회합니다.
     *
     * @param id operationId 또는 traceId
     * @return 200(terminal) / 202(비terminal) / 404(미존재)
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<OperationStatusResponse>> getResult(@PathVariable final String id) {
        log.trace("operation 상태 조회 요청. id={}", id);

        final Optional<UiOperationStatusView> found =
                operationQueryService.find(id, System.currentTimeMillis());
        if (found.isEmpty()) {
            log.debug("operation 없음. id={}", id);
            return ResponseEntity.notFound().build();
        }

        final UiOperationStatusView view = found.get();
        final OperationStatusResponse response = new OperationStatusResponse(
                view.operationId(),
                view.eqpId(),
                view.status().name(),
                view.phase().name(),
                view.desiredRevision(),
                view.startedAtEpochMs(),
                view.updatedAtEpochMs(),
                view.terminalAtEpochMs(),
                view.errorCode(),
                view.errorMsg(),
                view.repairReason());

        final HttpStatus httpStatus = view.terminal() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(httpStatus).body(ApiResponse.success(response));
    }
}
