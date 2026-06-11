package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.config.UiDualRequestProperties;
import com.nori.tc.ui.adapters.web.controller.support.EqpControllerCommandSupport;
import com.nori.tc.ui.adapters.web.controller.support.EqpControllerResponseSupport;
import com.nori.tc.ui.adapters.web.controller.support.UiPageRequestSupport;
import com.nori.tc.ui.adapters.web.dto.request.EqpCreateRequest;
import com.nori.tc.ui.adapters.web.dto.request.EqpLifecycleRequest;
import com.nori.tc.ui.adapters.web.dto.request.EqpUpdateRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.AsyncAcceptResponse;
import com.nori.tc.ui.adapters.web.dto.response.EqpInfoResponse;
import com.nori.tc.ui.adapters.web.dto.response.EqpManageDetailBatchResponse;
import com.nori.tc.ui.adapters.web.dto.response.EqpManageDetailResponse;
import com.nori.tc.ui.adapters.web.dto.response.EqpManageOptionsResponse;
import com.nori.tc.ui.adapters.web.dto.response.EqpRuntimeStateResponse;
import com.nori.tc.ui.core.eqp.EqpAsyncAcceptResult;
import com.nori.tc.ui.core.eqp.EqpCommandResult;
import com.nori.tc.ui.core.eqp.EqpManagementOptions;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.core.service.EqpManagementService;
import com.nori.tc.ui.domain.eqp.UiEqp;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * EQP 관리 REST API 컨트롤러입니다.
 *
 * <p>T2 기준으로 CRUD는 DB 저장과 runtime sync를 함께 수행하고,
 * START/END만 polling 기반 비동기 명령으로 유지합니다.</p>
 */
@RestController
@RequestMapping("/api/eqp")
@EnableConfigurationProperties(UiDualRequestProperties.class)
public class EqpController {

    private static final Logger log = LoggerFactory.getLogger(EqpController.class);
    private static final String TRACE_ID_MDC_KEY = "traceId";

    private final EqpQueryPort eqpQueryPort;
    private final EqpManagementService eqpManagementService;
    private final UiDualRequestProperties dualRequestProperties;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * @param eqpQueryPort 설비 조회 포트
     * @param eqpManagementService EQP 관리 orchestration 서비스
     * @param dualRequestProperties 비동기 timeout 설정
     */
    public EqpController(
            final EqpQueryPort eqpQueryPort,
            final EqpManagementService eqpManagementService,
            final UiDualRequestProperties dualRequestProperties
    ) {
        this.eqpQueryPort = Objects.requireNonNull(eqpQueryPort, "eqpQueryPort is null");
        this.eqpManagementService = Objects.requireNonNull(eqpManagementService, "eqpManagementService is null");
        this.dualRequestProperties = Objects.requireNonNull(dualRequestProperties, "dualRequestProperties is null");
    }

    /**
     * 설비 목록을 페이지 단위로 조회합니다.
     *
     * @param offset 조회 시작 위치
     * @param limit 조회 건수
     * @return 목록 페이지 응답
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<EqpInfoResponse>>> list(
            @RequestParam(name = "offset", required = false) final Integer offset,
            @RequestParam(name = "limit", required = false) final Integer limit
    ) {
        final UiPageRequest pageRequest = UiPageRequestSupport.resolve(offset, limit);

        if (log.isDebugEnabled()) {
            log.debug("설비 목록 조회 요청. offset={}, limit={}", pageRequest.offset(), pageRequest.limit());
        }

        final PagedResponse<UiEqp> page = eqpQueryPort.findAll(pageRequest);
        final PagedResponse<EqpInfoResponse> responsePage = EqpControllerResponseSupport.toEqpPage(page);

        return ResponseEntity.ok(ApiResponse.success(responsePage));
    }

    /**
     * 설비 ID 기준 단건을 조회합니다.
     *
     * @param eqpId 설비 ID
     * @return 단건 조회 응답
     */
    @GetMapping("/{eqpId}")
    public ResponseEntity<ApiResponse<EqpInfoResponse>> get(
            @PathVariable final String eqpId
    ) {
        final Optional<UiEqp> optionalEqp = eqpQueryPort.findByEqpId(eqpId);
        if (optionalEqp.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "설비를 찾을 수 없습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(EqpControllerResponseSupport.toEqpInfoResponse(optionalEqp.get())));
    }

    /**
     * EQP 관리 상세를 조회합니다.
     *
     * @param eqpId 설비 ID
     * @return 관리 상세 응답
     */
    @GetMapping("/{eqpId}/manage")
    public ResponseEntity<ApiResponse<EqpManageDetailResponse>> getManageDetail(
            @PathVariable final String eqpId
    ) {
        final Optional<EqpManagementSnapshot> snapshotOptional = eqpManagementService.getManageDetail(eqpId);
        if (snapshotOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "설비를 찾을 수 없습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(EqpControllerResponseSupport.toManageDetailResponse(snapshotOptional.get())));
    }

    /**
     * 여러 설비의 관리 상세를 한 번에 조회합니다.
     *
     * <p>그룹 화면(Gateway / gateway_app) 처럼 다건을 동시에 보여줘야 하는 화면에서
     * 클라이언트의 N-fan-out 호출을 제거하기 위해 사용합니다.</p>
     *
     * <p>경로는 의도적으로 {@code /manage-batch}로 둡니다. 단순 {@code /manage}는 기존
     * {@code GET /eqp/{eqpId}}와 path-pattern 매칭에서 모호해질 수 있어 안전하게 분리합니다.</p>
     *
     * @param eqpIds 콤마로 구분된 eqpId 목록 (예: {@code ?eqpIds=A,B,C})
     * @return 입력 순서를 보존한 batch 응답. 존재하지 않는 항목은 missingEqpIds에 담깁니다.
     */
    private static final int MANAGE_DETAIL_BATCH_LIMIT = 200;

    @GetMapping("/manage-batch")
    public ResponseEntity<ApiResponse<EqpManageDetailBatchResponse>> getManageDetailBatch(
            @RequestParam(name = "eqpIds") final String eqpIds
    ) {
        if (eqpIds == null || eqpIds.isBlank()) {
            throw new UiBadRequestException("eqpIds는 비어 있을 수 없습니다.");
        }

        // CSV 입력을 trim + 중복 제거하여 순서 보존된 list로 정규화합니다.
        final List<String> requestedEqpIds = Arrays.stream(eqpIds.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
        if (requestedEqpIds.isEmpty()) {
            throw new UiBadRequestException("유효한 eqpId가 없습니다.");
        }
        if (requestedEqpIds.size() > MANAGE_DETAIL_BATCH_LIMIT) {
            throw new UiBadRequestException(
                    "한 번에 조회 가능한 설비 수는 " + MANAGE_DETAIL_BATCH_LIMIT + "개 이하여야 합니다."
            );
        }

        final Map<String, EqpManagementSnapshot> snapshots =
                eqpManagementService.getManageDetailsByEqpIds(requestedEqpIds);
        return ResponseEntity.ok(ApiResponse.success(
                EqpControllerResponseSupport.toManageDetailBatchResponse(requestedEqpIds, snapshots)
        ));
    }

    /**
     * EQP 관리 화면 옵션을 조회합니다.
     *
     * @return 관리 옵션 응답
     */
    @GetMapping("/options")
    public ResponseEntity<ApiResponse<EqpManageOptionsResponse>> getOptions() {
        final EqpManagementOptions options = eqpManagementService.getOptions();
        return ResponseEntity.ok(ApiResponse.success(EqpControllerResponseSupport.toManageOptionsResponse(options)));
    }

    /**
     * 설비 ID에 매핑된 파라미터 버전 목록을 조회합니다.
     *
     * @param eqpId 설비 ID
     * @return 버전 목록 응답
     */
    @GetMapping("/{eqpId}/param-versions")
    public ResponseEntity<ApiResponse<List<String>>> getParamVersions(
            @PathVariable final String eqpId
    ) {
        final Optional<UiEqp> optionalEqp = eqpQueryPort.findByEqpId(eqpId);
        if (optionalEqp.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "설비를 찾을 수 없습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(eqpQueryPort.findParamVersionsByEqpId(eqpId)));
    }

    /**
     * 설비 ID에 매핑된 런타임 상태를 조회합니다.
     *
     * @param eqpId 설비 ID
     * @return 런타임 상태 응답
     */
    @GetMapping("/{eqpId}/runtime-state")
    public ResponseEntity<ApiResponse<EqpRuntimeStateResponse>> getRuntimeState(
            @PathVariable final String eqpId
    ) {
        final Optional<EqpQueryPort.EqpRuntimeStateView> optionalState = eqpQueryPort.findRuntimeStateByEqpId(eqpId);
        if (optionalState.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "설비를 찾을 수 없습니다."));
        }

        final EqpQueryPort.EqpRuntimeStateView state = optionalState.get();
        return ResponseEntity.ok(ApiResponse.success(new EqpRuntimeStateResponse(
                state.controlState(),
                state.eqpState(),
                state.connectionState()
        )));
    }

    /**
     * EQP를 생성하고 runtime sync까지 완료합니다.
     *
     * @param request 생성 요청
     * @param authentication 현재 인증 정보
     * @return 비동기 처리 결과
     */
    @PostMapping
    public DeferredResult<ResponseEntity<ApiResponse<Void>>> create(
            @Valid @RequestBody final EqpCreateRequest request,
            final Authentication authentication
    ) {
        log.info("EQP 생성 요청. eqpId={}, interfaceType={}", request.eqpId(), request.interfaceType());
        return submitManagementCommand(
                eqpManagementService.create(
                        EqpControllerCommandSupport.toCreateCommand(request, authentication),
                        dualRequestProperties.getDualRequestTimeoutMs()
                ),
                "EQP_CREATE",
                request.eqpId()
        );
    }

    /**
     * EQP를 수정하고 runtime sync까지 완료합니다.
     *
     * @param eqpId 설비 ID
     * @param request 수정 요청
     * @param authentication 현재 인증 정보
     * @return 비동기 처리 결과
     */
    @PutMapping("/{eqpId}")
    public DeferredResult<ResponseEntity<ApiResponse<Void>>> update(
            @PathVariable final String eqpId,
            @Valid @RequestBody final EqpUpdateRequest request,
            final Authentication authentication
    ) {
        log.info("EQP 수정 요청. eqpId={}", eqpId);
        final Optional<EqpManagementSnapshot> snapshotOptional = eqpManagementService.getManageDetail(eqpId);
        if (snapshotOptional.isEmpty()) {
            final DeferredResult<ResponseEntity<ApiResponse<Void>>> deferredResult = new DeferredResult<>();
            deferredResult.setResult(
                    ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(ApiResponse.error("NOT_FOUND", "설비를 찾을 수 없습니다."))
            );
            return deferredResult;
        }
        return submitManagementCommand(
                eqpManagementService.update(
                        eqpId,
                        EqpControllerCommandSupport.toUpdateCommand(request, snapshotOptional.get(), authentication),
                        dualRequestProperties.getDualRequestTimeoutMs()
                ),
                "EQP_UPDATE",
                eqpId
        );
    }

    /**
     * EQP를 종료 후 삭제하고 runtime sync까지 완료합니다.
     *
     * @param eqpId 설비 ID
     * @return 비동기 처리 결과
     */
    @DeleteMapping("/{eqpId}")
    public DeferredResult<ResponseEntity<ApiResponse<Void>>> delete(
            @PathVariable final String eqpId
    ) {
        log.info("EQP 삭제 요청. eqpId={}", eqpId);
        return submitManagementCommand(
                eqpManagementService.delete(eqpId, dualRequestProperties.getDualRequestTimeoutMs()),
                "EQP_DELETE",
                eqpId
        );
    }

    /**
     * 설비 시작 명령을 Gateway에 전송합니다.
     *
     * @param eqpId 설비 ID
     * @param request 시작 요청
     * @return 202 Accepted 응답
     */
    @PostMapping("/{eqpId}/start")
    public ResponseEntity<ApiResponse<AsyncAcceptResponse>> start(
            @PathVariable final String eqpId,
            @Valid @RequestBody final EqpLifecycleRequest request
    ) {
        final EqpAsyncAcceptResult acceptResult = eqpManagementService.startAsync(
                eqpId,
                request.interfaceType(),
                request.uiMessage(),
                dualRequestProperties.getDualRequestTimeoutMs()
        );

        try (MdcTraceScope ignored = openTraceMdcScope(acceptResult.traceId())) {
            log.info("EQP_START 요청. eqpId={}, interfaceType={}, traceId={}",
                    eqpId, request.interfaceType(), acceptResult.traceId());
            return toAsyncAcceptResponse(acceptResult);
        }
    }

    /**
     * 설비 종료 명령을 Gateway에 전송합니다.
     *
     * @param eqpId 설비 ID
     * @param request 종료 요청
     * @return 202 Accepted 응답
     */
    @PostMapping("/{eqpId}/end")
    public ResponseEntity<ApiResponse<AsyncAcceptResponse>> end(
            @PathVariable final String eqpId,
            @Valid @RequestBody final EqpLifecycleRequest request
    ) {
        final EqpAsyncAcceptResult acceptResult = eqpManagementService.endAsync(
                eqpId,
                request.interfaceType(),
                request.uiMessage(),
                dualRequestProperties.getDualRequestTimeoutMs()
        );

        try (MdcTraceScope ignored = openTraceMdcScope(acceptResult.traceId())) {
            log.info("EQP_END 요청. eqpId={}, interfaceType={}, traceId={}",
                    eqpId, request.interfaceType(), acceptResult.traceId());
            return toAsyncAcceptResponse(acceptResult);
        }
    }

    /**
     * PLC 폴링 그룹 시작 명령을 Gateway에 전송합니다(EQP_PLC_GROUP_START).
     *
     * <p>경로가 {@code /api/eqp/**} 하위라 기존 {@code EQP_WRITE}(PREFIX /api/eqp, 전 메서드) 권한이 자동 커버합니다.</p>
     *
     * @param eqpId 설비 ID
     * @param groupMessageName 폴링 그룹 messageName
     * @return 202 Accepted 응답
     */
    @PostMapping("/{eqpId}/polling-group/{groupMessageName}/start")
    public ResponseEntity<ApiResponse<AsyncAcceptResponse>> startPollingGroup(
            @PathVariable final String eqpId,
            @PathVariable final String groupMessageName
    ) {
        final EqpAsyncAcceptResult acceptResult = eqpManagementService.startPollingGroupAsync(
                eqpId,
                groupMessageName,
                dualRequestProperties.getDualRequestTimeoutMs()
        );

        try (MdcTraceScope ignored = openTraceMdcScope(acceptResult.traceId())) {
            log.info("EQP_PLC_GROUP_START 요청. eqpId={}, group={}, traceId={}",
                    eqpId, groupMessageName, acceptResult.traceId());
            return toAsyncAcceptResponse(acceptResult);
        }
    }

    /**
     * PLC 폴링 그룹 중단 명령을 Gateway에 전송합니다(EQP_PLC_GROUP_END).
     *
     * @param eqpId 설비 ID
     * @param groupMessageName 폴링 그룹 messageName
     * @return 202 Accepted 응답
     */
    @PostMapping("/{eqpId}/polling-group/{groupMessageName}/end")
    public ResponseEntity<ApiResponse<AsyncAcceptResponse>> endPollingGroup(
            @PathVariable final String eqpId,
            @PathVariable final String groupMessageName
    ) {
        final EqpAsyncAcceptResult acceptResult = eqpManagementService.endPollingGroupAsync(
                eqpId,
                groupMessageName,
                dualRequestProperties.getDualRequestTimeoutMs()
        );

        try (MdcTraceScope ignored = openTraceMdcScope(acceptResult.traceId())) {
            log.info("EQP_PLC_GROUP_END 요청. eqpId={}, group={}, traceId={}",
                    eqpId, groupMessageName, acceptResult.traceId());
            return toAsyncAcceptResponse(acceptResult);
        }
    }

    /**
     * 관리 명령 future를 HTTP 응답으로 변환합니다.
     *
     * @param future 처리 future
     * @param action 로그용 액션명
     * @param eqpId 설비 ID
     * @return DeferredResult 응답
     */
    private DeferredResult<ResponseEntity<ApiResponse<Void>>> submitManagementCommand(
            final CompletableFuture<EqpCommandResult> future,
            final String action,
            final String eqpId
    ) {
        final DeferredResult<ResponseEntity<ApiResponse<Void>>> deferredResult = new DeferredResult<>();

        future.whenComplete((result, throwable) -> {
            if (deferredResult.isSetOrExpired()) {
                return;
            }

            if (throwable != null) {
                log.error("EQP 관리 요청 처리 중 비정상 오류가 발생했습니다. action={}, eqpId={}", action, eqpId, throwable);
                deferredResult.setResult(ResponseEntity.internalServerError()
                        .body(ApiResponse.error("INTERNAL_ERROR", "처리 중 내부 오류가 발생했습니다.")));
                return;
            }

            if (result.success()) {
                log.info("EQP 관리 요청 완료. action={}, eqpId={}, statusCode={}", action, eqpId, result.statusCode());
            } else {
                log.warn("EQP 관리 요청 실패. action={}, eqpId={}, statusCode={}, errorCode={}",
                        action, eqpId, result.statusCode(), result.errorCode());
            }
            deferredResult.setResult(EqpControllerResponseSupport.toCommandResponse(result));
        });

        return deferredResult;
    }

    private ResponseEntity<ApiResponse<AsyncAcceptResponse>> toAsyncAcceptResponse(final EqpAsyncAcceptResult acceptResult) {
        if (!acceptResult.accepted()) {
            return ResponseEntity.status(acceptResult.statusCode())
                    .body(ApiResponse.error(acceptResult.errorCode(), acceptResult.errorMessage()));
        }
        return ResponseEntity.accepted()
                .body(ApiResponse.success(new AsyncAcceptResponse(acceptResult.traceId())));
    }

    /**
     * 현재 스레드 MDC에 traceId를 주입하고 스코프 종료 시 이전 값을 복구합니다.
     *
     * @param traceId trace id
     * @return MDC 스코프
     */
    private static MdcTraceScope openTraceMdcScope(final String traceId) {
        final String previousTraceId = MDC.get(TRACE_ID_MDC_KEY);
        if (traceId == null || traceId.isBlank()) {
            MDC.remove(TRACE_ID_MDC_KEY);
        } else {
            MDC.put(TRACE_ID_MDC_KEY, traceId);
        }
        return new MdcTraceScope(previousTraceId);
    }

    /**
     * MDC traceId 복구를 담당하는 스코프입니다.
     *
     * @param previousTraceId 이전 trace id
     */
    private record MdcTraceScope(String previousTraceId) implements AutoCloseable {
        @Override
        public void close() {
            if (previousTraceId == null || previousTraceId.isBlank()) {
                MDC.remove(TRACE_ID_MDC_KEY);
                return;
            }
            MDC.put(TRACE_ID_MDC_KEY, previousTraceId);
        }
    }
}
