package com.nori.tc.ui.adapters.web.controller;

import tools.jackson.databind.JsonNode;
import com.nori.tc.messaging.kafka.contract.GatewayEquipmentProfileSnapshot;
import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.domain.eqp.TcEqp;
import com.nori.tc.ui.adapters.web.config.UiDualRequestProperties;
import com.nori.tc.ui.adapters.web.controller.support.UiPageRequestSupport;
import com.nori.tc.ui.adapters.web.dto.request.EqpCreateRequest;
import com.nori.tc.ui.adapters.web.dto.request.EqpLifecycleRequest;
import com.nori.tc.ui.adapters.web.dto.request.EqpManagementRequestSupport;
import com.nori.tc.ui.adapters.web.dto.request.EqpUpdateRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.AsyncAcceptResponse;
import com.nori.tc.ui.adapters.web.dto.response.EqpInfoResponse;
import com.nori.tc.ui.adapters.web.dto.response.EqpManageDetailResponse;
import com.nori.tc.ui.adapters.web.dto.response.EqpManageOptionsResponse;
import com.nori.tc.ui.adapters.web.dto.response.EqpRuntimeStateResponse;
import com.nori.tc.ui.core.eqp.EqpCommandResult;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementOptions;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.model.UiCommandMessage;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.core.port.messaging.UiGatewayEventPublishPort;
import com.nori.tc.ui.core.port.redis.AsyncResultStorePort;
import com.nori.tc.ui.core.service.EqpManagementService;
import com.nori.tc.ui.domain.auth.UserPrincipal;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
    private static final String EDIT_PARAM_VERSION = "EDIT";
    private static final String UI_SOURCE = "TC-UI-APP";

    private final EqpQueryPort eqpQueryPort;
    private final EqpManagementService eqpManagementService;
    private final UiGatewayEventPublishPort gatewayEventPublishPort;
    private final AsyncResultStorePort asyncResultStorePort;
    private final UiDualRequestProperties dualRequestProperties;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * @param eqpQueryPort 설비 조회 포트
     * @param eqpManagementService EQP 관리 orchestration 서비스
     * @param gatewayEventPublishPort Gateway Kafka 이벤트 발행 포트
     * @param asyncResultStorePort START/END polling 상태 저장 포트
     * @param dualRequestProperties 비동기 timeout 설정
     */
    public EqpController(
            final EqpQueryPort eqpQueryPort,
            final EqpManagementService eqpManagementService,
            final UiGatewayEventPublishPort gatewayEventPublishPort,
            final AsyncResultStorePort asyncResultStorePort,
            final UiDualRequestProperties dualRequestProperties
    ) {
        this.eqpQueryPort = Objects.requireNonNull(eqpQueryPort, "eqpQueryPort is null");
        this.eqpManagementService = Objects.requireNonNull(eqpManagementService, "eqpManagementService is null");
        this.gatewayEventPublishPort = Objects.requireNonNull(gatewayEventPublishPort, "gatewayEventPublishPort is null");
        this.asyncResultStorePort = Objects.requireNonNull(asyncResultStorePort, "asyncResultStorePort is null");
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
        final PageRequest pageRequest = UiPageRequestSupport.resolve(offset, limit);

        if (log.isDebugEnabled()) {
            log.debug("설비 목록 조회 요청. offset={}, limit={}", pageRequest.offset(), pageRequest.limit());
        }

        final PagedResponse<TcEqp> page = eqpQueryPort.findAll(pageRequest);
        final PagedResponse<EqpInfoResponse> responsePage = toEqpPage(page);

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
        final Optional<TcEqp> optionalEqp = eqpQueryPort.findByEqpId(eqpId);
        if (optionalEqp.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "설비를 찾을 수 없습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(toEqpInfoResponse(optionalEqp.get())));
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

        return ResponseEntity.ok(ApiResponse.success(toManageDetailResponse(snapshotOptional.get())));
    }

    /**
     * EQP 관리 화면 옵션을 조회합니다.
     *
     * @return 관리 옵션 응답
     */
    @GetMapping("/options")
    public ResponseEntity<ApiResponse<EqpManageOptionsResponse>> getOptions() {
        final EqpManagementOptions options = eqpManagementService.getOptions();
        return ResponseEntity.ok(ApiResponse.success(toManageOptionsResponse(options)));
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
        final Optional<TcEqp> optionalEqp = eqpQueryPort.findByEqpId(eqpId);
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
                eqpManagementService.create(toCreateCommand(request, authentication), dualRequestProperties.getDualRequestTimeoutMs()),
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
        return submitManagementCommand(
                eqpManagementService.update(eqpId, toUpdateCommand(request, authentication), dualRequestProperties.getDualRequestTimeoutMs()),
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
        final String traceId = generateTraceId();

        try (MdcTraceScope ignored = openTraceMdcScope(traceId)) {
            log.info("EQP_START 요청. eqpId={}, interfaceType={}, traceId={}",
                    eqpId, request.interfaceType(), traceId);

            final UiCommandMessage message = buildMessage(
                    UiCommandEventType.EQP_START, traceId, eqpId,
                    request.interfaceType(), request.uiMessage(), null
            );

            return publishLifecycleAndAccept(UiCommandEventType.EQP_START, traceId, eqpId, message);
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
        final String traceId = generateTraceId();

        try (MdcTraceScope ignored = openTraceMdcScope(traceId)) {
            log.info("EQP_END 요청. eqpId={}, interfaceType={}, traceId={}",
                    eqpId, request.interfaceType(), traceId);

            final UiCommandMessage message = buildMessage(
                    UiCommandEventType.EQP_END, traceId, eqpId,
                    request.interfaceType(), request.uiMessage(), null
            );

            return publishLifecycleAndAccept(UiCommandEventType.EQP_END, traceId, eqpId, message);
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
            deferredResult.setResult(toCommandResponse(result));
        });

        return deferredResult;
    }

    /**
     * START/END lifecycle 이벤트를 Gateway에 발행하고 polling용 traceId를 반환합니다.
     *
     * @param eventType 이벤트 타입
     * @param traceId trace id
     * @param eqpId 설비 ID
     * @param message 발행 메시지
     * @return 202 Accepted 응답
     */
    private ResponseEntity<ApiResponse<AsyncAcceptResponse>> publishLifecycleAndAccept(
            final UiCommandEventType eventType,
            final String traceId,
            final String eqpId,
            final UiCommandMessage message
    ) {
        final long lifecycleTimeoutMs = dualRequestProperties.getDualRequestTimeoutMs();
        asyncResultStorePort.registerPending(traceId, lifecycleTimeoutMs);

        try {
            gatewayEventPublishPort.publish(message);
            log.info("{} Kafka 발행 완료. eqpId={}, traceId={}", eventType, eqpId, traceId);
        } catch (Exception exception) {
            log.error("{} Kafka 발행 실패. eqpId={}, traceId={}", eventType, eqpId, traceId, exception);
            asyncResultStorePort.markTimeout(traceId);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("PUBLISH_FAILED", "Kafka 발행 중 오류가 발생했습니다."));
        }

        return ResponseEntity.accepted()
                .body(ApiResponse.success(new AsyncAcceptResponse(traceId)));
    }

    /**
     * UI 명령 메시지를 생성합니다.
     *
     * @param eventType 이벤트 타입
     * @param traceId trace id
     * @param eqpId 설비 ID
     * @param interfaceType 통신 인터페이스 문자열
     * @param uiMessage UI payload(JSON object)
     * @param equipmentProfile 설비 프로파일
     * @return UI 명령 메시지
     */
    private UiCommandMessage buildMessage(
            final UiCommandEventType eventType,
            final String traceId,
            final String eqpId,
            final String interfaceType,
            final JsonNode uiMessage,
            final GatewayEquipmentProfileSnapshot equipmentProfile
    ) {
        return new UiCommandMessage(
                eventType,
                traceId,
                UI_SOURCE,
                eqpId,
                interfaceType,
                uiMessage,
                equipmentProfile
        );
    }

    /**
     * EQP 생성 요청 DTO를 core 명령으로 변환합니다.
     *
     * @param request 요청 DTO
     * @param authentication 현재 인증 정보
     * @return core 명령
     */
    private static EqpManagementCommand.Create toCreateCommand(
            final EqpCreateRequest request,
            final Authentication authentication
    ) {
        return new EqpManagementCommand.Create(
                resolveCurrentUser(authentication),
                request.eqpId(),
                request.interfaceType(),
                request.commMode(),
                request.isDev(),
                request.routePartition(),
                request.eqpIp(),
                request.eqpPort(),
                request.modelVersionKey(),
                request.appliedParamVersion(),
                request.gatewayJarFileName(),
                request.businessJarFileName(),
                toLogSettings(request.logSettings()),
                toHsmsSettings(request.hsmsSettings()),
                toSocketSettings(request.socketSettings())
        );
    }

    /**
     * EQP 수정 요청 DTO를 core 명령으로 변환합니다.
     *
     * @param request 요청 DTO
     * @param authentication 현재 인증 정보
     * @return core 명령
     */
    private static EqpManagementCommand.Update toUpdateCommand(
            final EqpUpdateRequest request,
            final Authentication authentication
    ) {
        return new EqpManagementCommand.Update(
                resolveCurrentUser(authentication),
                request.commMode(),
                request.isDev(),
                request.routePartition(),
                request.eqpIp(),
                request.eqpPort(),
                request.modelVersionKey(),
                request.appliedParamVersion(),
                request.gatewayJarFileName(),
                request.businessJarFileName(),
                toLogSettings(request.logSettings()),
                toHsmsSettings(request.hsmsSettings()),
                toSocketSettings(request.socketSettings())
        );
    }

    /**
     * 로그 정책 요청 DTO를 core 명령으로 변환합니다.
     *
     * @param request 로그 정책 요청 DTO
     * @return core 로그 정책 명령
     */
    private static EqpManagementCommand.LogSettings toLogSettings(
            final EqpManagementRequestSupport.LogSettingsRequest request
    ) {
        if (request == null) {
            return null;
        }
        return new EqpManagementCommand.LogSettings(
                request.logLevel(),
                request.logRetentionDays(),
                request.logPath()
        );
    }

    /**
     * SECS 설정 요청 DTO를 core 명령으로 변환합니다.
     *
     * @param request SECS 설정 요청 DTO
     * @return core SECS 명령
     */
    private static EqpManagementCommand.HsmsSettings toHsmsSettings(
            final EqpManagementRequestSupport.HsmsSettingsRequest request
    ) {
        if (request == null) {
            return null;
        }
        return new EqpManagementCommand.HsmsSettings(
                request.deviceId(),
                request.t3Timeout(),
                request.t5Timeout(),
                request.t6Timeout(),
                request.t7Timeout(),
                request.t8Timeout(),
                request.linkTestEnabled(),
                request.linkTestInterval(),
                request.maxMsgBytes()
        );
    }

    /**
     * SOCKET 설정 요청 DTO를 core 명령으로 변환합니다.
     *
     * @param request SOCKET 설정 요청 DTO
     * @return core SOCKET 명령
     */
    private static EqpManagementCommand.SocketSettings toSocketSettings(
            final EqpManagementRequestSupport.SocketSettingsRequest request
    ) {
        if (request == null) {
            return null;
        }
        return new EqpManagementCommand.SocketSettings(
                request.socketProtocolType(),
                request.charset(),
                request.heartbeatEnabled(),
                request.heartbeatInterval(),
                request.readTimeout(),
                request.writeTimeout(),
                request.maxFrameSizeBytes(),
                request.keepAliveEnabled()
        );
    }

    /**
     * 관리 명령 결과를 HTTP 응답으로 변환합니다.
     *
     * @param result 명령 처리 결과
     * @return HTTP 응답
     */
    private static ResponseEntity<ApiResponse<Void>> toCommandResponse(final EqpCommandResult result) {
        if (result.success()) {
            return ResponseEntity.status(result.statusCode()).body(ApiResponse.success(null));
        }
        return ResponseEntity.status(result.statusCode())
                .body(ApiResponse.error(result.errorCode(), result.errorMessage()));
    }

    /**
     * EQP 관리 상세 스냅샷을 응답 DTO로 변환합니다.
     *
     * @param snapshot 관리 스냅샷
     * @return 응답 DTO
     */
    private static EqpManageDetailResponse toManageDetailResponse(final EqpManagementSnapshot snapshot) {
        final List<EqpManageDetailResponse.ParamVersionOptionResponse> paramVersions = toParamVersionOptions(snapshot);
        final AppliedParamVersionView appliedParamView = resolveAppliedParamVersionView(
                snapshot.eqp().appliedParamVersion(),
                paramVersions
        );

        return new EqpManageDetailResponse(
                snapshot.eqp().eqpId(),
                snapshot.eqp().commInterface(),
                snapshot.eqp().commMode(),
                snapshot.eqp().isDev(),
                snapshot.eqp().routePartition(),
                snapshot.eqp().eqpIp(),
                snapshot.eqp().eqpPort(),
                snapshot.eqp().enabled(),
                snapshot.runtimeState() == null
                        ? null
                        : new EqpManageDetailResponse.RuntimeStateResponse(
                        snapshot.runtimeState().controlState(),
                        snapshot.runtimeState().eqpState(),
                        snapshot.connectionState()
                ),
                snapshot.logPolicy() == null
                        ? null
                        : new EqpManageDetailResponse.LogPolicyResponse(
                        snapshot.logPolicy().logLevel(),
                        snapshot.logPolicy().logRetentionDays(),
                        snapshot.logPolicy().logPath()
                ),
                new EqpManageDetailResponse.JarBindingResponse(
                        snapshot.gatewayJar() == null ? null : snapshot.gatewayJar().jarFileName(),
                        snapshot.businessJar() == null ? null : snapshot.businessJar().jarFileName()
                ),
                snapshot.model() == null
                        ? null
                        : new EqpManageDetailResponse.ModelBindingResponse(
                        snapshot.model().modelVersionKey(),
                        snapshot.model().modelKey(),
                        snapshot.model().modelName(),
                        snapshot.model().parentModel(),
                        snapshot.model().modelVersion(),
                        snapshot.model().commInterface(),
                        snapshot.model().status()
                ),
                snapshot.hsms() == null
                        ? null
                        : new EqpManageDetailResponse.HsmsSettingsResponse(
                        snapshot.hsms().deviceId(),
                        snapshot.hsms().t3Timeout(),
                        snapshot.hsms().t5Timeout(),
                        snapshot.hsms().t6Timeout(),
                        snapshot.hsms().t7Timeout(),
                        snapshot.hsms().t8Timeout(),
                        snapshot.hsms().linkTestEnabled(),
                        snapshot.hsms().linkTestInterval(),
                        snapshot.hsms().maxMsgBytes()
                ),
                snapshot.socket() == null
                        ? null
                        : new EqpManageDetailResponse.SocketSettingsResponse(
                        snapshot.socket().socketProtocolType(),
                        snapshot.socket().charset(),
                        snapshot.socket().heartbeatEnabled(),
                        snapshot.socket().heartbeatInterval(),
                        snapshot.socket().readTimeout(),
                        snapshot.socket().writeTimeout(),
                        snapshot.socket().maxFrameSizeBytes(),
                        snapshot.socket().keepAliveEnabled()
                ),
                appliedParamView.version(),
                appliedParamView.description(),
                paramVersions,
                snapshot.portStatuses().stream()
                        .map(portStatus -> new EqpManageDetailResponse.PortStatusResponse(
                                portStatus.portId(),
                                portStatus.portType(),
                                portStatus.portState(),
                                portStatus.carrierId(),
                                portStatus.carrierType(),
                                portStatus.carrierState(),
                                portStatus.updatedAt()
                        ))
                        .toList()
        );
    }

    /**
     * EQP 옵션을 응답 DTO로 변환합니다.
     *
     * @param options 옵션 묶음
     * @return 옵션 응답 DTO
     */
    private static EqpManageOptionsResponse toManageOptionsResponse(final EqpManagementOptions options) {
        return new EqpManageOptionsResponse(
                options.socketProtocolTypes(),
                options.gatewayJarFileNames(),
                options.businessJarFileNames(),
                options.developModelOptions().stream()
                        .map(EqpController::toModelOptionResponse)
                        .toList(),
                options.operateModelOptions().stream()
                        .map(EqpController::toModelOptionResponse)
                        .toList()
        );
    }

    /**
     * EQP 파라미터 목록을 버전 기준 옵션으로 축약합니다.
     *
     * @param snapshot 관리 스냅샷
     * @return 버전 옵션 목록
     */
    private static List<EqpManageDetailResponse.ParamVersionOptionResponse> toParamVersionOptions(
            final EqpManagementSnapshot snapshot
    ) {
        final LinkedHashMap<String, String> paramVersionDescriptions = new LinkedHashMap<>();

        snapshot.paramVersionMetas().forEach(paramVersionMeta -> {
            final String normalizedVersion = normalizeText(paramVersionMeta.paramVersion());
            if (normalizedVersion == null || EDIT_PARAM_VERSION.equals(normalizedVersion)) {
                return;
            }
            paramVersionDescriptions.putIfAbsent(
                    normalizedVersion,
                    normalizeText(paramVersionMeta.versionDescription())
            );
        });

        snapshot.params().forEach(param -> {
            final String normalizedVersion = normalizeText(param.paramVersion());
            // EDIT 버전은 내부 체크아웃 잠금용이므로 관리 summary/dropdown에 노출하지 않습니다.
            if (normalizedVersion == null || EDIT_PARAM_VERSION.equals(normalizedVersion)) {
                return;
            }
            // 신규 메타 테이블이 비어 있는 legacy 데이터셋은 tc_eqp_param.description을 fallback으로만 사용합니다.
            paramVersionDescriptions.putIfAbsent(normalizedVersion, normalizeText(param.description()));
        });

        return paramVersionDescriptions.entrySet().stream()
                .map(entry -> new EqpManageDetailResponse.ParamVersionOptionResponse(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * applied_param_version 저장 컬럼과 legacy fallback 규칙을 한 곳에서 계산합니다.
     *
     * <p>정책:</p>
     * <ul>
     *   <li>저장 컬럼 값이 있으면 그 값을 그대로 사용</li>
     *   <li>컬럼 값이 비어 있으면 legacy 호환을 위해 첫 번째 summary version으로 fallback</li>
     * </ul>
     *
     * @param storedAppliedParamVersion tc_eqp.applied_param_version 저장값
     * @param paramVersions 버전 summary 목록
     * @return 현재 적용 버전/설명 뷰
     */
    private static AppliedParamVersionView resolveAppliedParamVersionView(
            final String storedAppliedParamVersion,
            final List<EqpManageDetailResponse.ParamVersionOptionResponse> paramVersions
    ) {
        final String appliedParamVersion = normalizeText(storedAppliedParamVersion);
        if (appliedParamVersion != null) {
            return new AppliedParamVersionView(
                    appliedParamVersion,
                    resolveAppliedParamDescription(paramVersions, appliedParamVersion)
            );
        }

        final EqpManageDetailResponse.ParamVersionOptionResponse fallback = paramVersions.stream()
                .findFirst()
                .orElse(null);
        if (fallback == null) {
            return new AppliedParamVersionView(null, null);
        }

        return new AppliedParamVersionView(
                normalizeText(fallback.paramVersion()),
                normalizeText(fallback.description())
        );
    }

    /**
     * 적용 버전에 대응하는 설명을 summary 목록에서 조회합니다.
     *
     * @param paramVersions 버전 summary 목록
     * @param appliedParamVersion 현재 적용 버전
     * @return 적용 버전 설명
     */
    private static String resolveAppliedParamDescription(
            final List<EqpManageDetailResponse.ParamVersionOptionResponse> paramVersions,
            final String appliedParamVersion
    ) {
        return paramVersions.stream()
                .filter(option -> appliedParamVersion.equals(normalizeText(option.paramVersion())))
                .map(EqpManageDetailResponse.ParamVersionOptionResponse::description)
                .findFirst()
                .orElse(null);
    }

    /**
     * 모델 옵션을 응답 DTO로 변환합니다.
     *
     * @param option core 모델 옵션
     * @return 응답 DTO
     */
    private static EqpManageOptionsResponse.ModelOptionResponse toModelOptionResponse(
            final EqpManagementOptions.ModelOption option
    ) {
        return new EqpManageOptionsResponse.ModelOptionResponse(
                option.modelVersionKey(),
                option.modelKey(),
                option.modelName(),
                option.parentModel(),
                option.modelVersion(),
                option.commInterface(),
                option.status()
        );
    }

    /**
     * 설비 도메인 페이지를 응답 DTO 페이지로 변환합니다.
     *
     * @param page 도메인 페이지
     * @return 응답 페이지
     */
    private static PagedResponse<EqpInfoResponse> toEqpPage(final PagedResponse<TcEqp> page) {
        return PagedResponse.of(
                page.items().stream()
                        .map(EqpController::toEqpInfoResponse)
                        .toList(),
                page.offset(),
                page.limit(),
                page.count()
        );
    }

    /**
     * 설비 도메인을 응답 DTO로 변환합니다.
     *
     * @param eqp 설비 도메인
     * @return 응답 DTO
     */
    private static EqpInfoResponse toEqpInfoResponse(final TcEqp eqp) {
        return new EqpInfoResponse(
                eqp.eqpKey(),
                eqp.eqpId(),
                eqp.commInterface(),
                eqp.commMode(),
                eqp.isDev(),
                eqp.routePartition(),
                eqp.eqpIp(),
                eqp.eqpPort(),
                eqp.modelVersionKey(),
                eqp.enabled(),
                eqp.createdAt(),
                eqp.updatedAt(),
                eqp.createdBy(),
                eqp.updatedBy()
        );
    }

    /**
     * 현재 인증 정보에서 사용자 ID를 추출합니다.
     *
     * @param authentication 현재 인증 정보
     * @return 사용자 ID 또는 SYSTEM
     */
    private static String resolveCurrentUser(final Authentication authentication) {
        if (authentication == null) {
            return "SYSTEM";
        }
        if (authentication.getPrincipal() instanceof UserPrincipal principal
                && principal.userId() != null
                && !principal.userId().isBlank()) {
            return principal.userId();
        }
        if (authentication.getName() == null || authentication.getName().isBlank()) {
            return "SYSTEM";
        }
        return authentication.getName();
    }

    /**
     * 공백 문자열을 null로 정규화합니다.
     *
     * @param value 입력 문자열
     * @return 정규화 결과
     */
    private static String normalizeText(final String value) {
        if (value == null) {
            return null;
        }
        final String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 요청별 고유 trace id를 생성합니다.
     *
     * @return UUID 문자열
     */
    private static String generateTraceId() {
        return UUID.randomUUID().toString();
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

    /**
     * 현재 적용 param version과 설명을 함께 보관하는 내부 뷰입니다.
     */
    private record AppliedParamVersionView(
            String version,
            String description
    ) {
    }
}
