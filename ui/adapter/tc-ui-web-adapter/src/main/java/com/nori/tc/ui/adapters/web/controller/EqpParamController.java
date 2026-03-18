package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.db.domain.eqp.TcEqpParam;
import com.nori.tc.ui.adapters.web.dto.request.EqpCheckinRequest;
import com.nori.tc.ui.adapters.web.dto.request.EqpCheckoutRequest;
import com.nori.tc.ui.adapters.web.dto.request.EqpParamSaveRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.EqpCheckoutStatusResponse;
import com.nori.tc.ui.adapters.web.dto.response.EqpParamResponse;
import com.nori.tc.ui.core.exception.EqpAlreadyCheckedOutException;
import com.nori.tc.ui.core.port.db.EqpParamCommandPort;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.domain.auth.UserPrincipal;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 설비(EQP) 파라미터 체크아웃/저장/체크인 REST API 컨트롤러입니다.
 *
 * <p>제공 엔드포인트:</p>
 * <ul>
 *   <li>GET  /api/eqp/{eqpId}/checkout-status — 체크아웃 상태 조회</li>
 *   <li>GET  /api/eqp/{eqpId}/params/{version} — 특정 버전 파라미터 목록 조회</li>
 *   <li>POST /api/eqp/{eqpId}/checkout         — 체크아웃 (EDIT 버전 생성)</li>
 *   <li>DELETE /api/eqp/{eqpId}/checkout       — 체크아웃 되돌리기 (EDIT 버전 삭제)</li>
 *   <li>PUT  /api/eqp/{eqpId}/edit-params       — EDIT 파라미터 저장</li>
 *   <li>POST /api/eqp/{eqpId}/checkin           — 체크인 (EDIT → 신규 버전, EDIT 삭제)</li>
 * </ul>
 *
 * <p>체크아웃 잠금 메커니즘: {@code tc_eqp_param.param_version='EDIT'} 존재 여부 = 체크아웃 상태</p>
 *
 * <p>현재 사용자 ID: 인증 principal의 {@link UserPrincipal#userId()}를 우선 사용하고,
 * 비표준 principal일 때만 {@link Authentication#getName()}으로 fallback 합니다.</p>
 */
@RestController
@RequestMapping("/api/eqp")
public class EqpParamController {

    private static final Logger log = LoggerFactory.getLogger(EqpParamController.class);

    private final EqpQueryPort eqpQueryPort;
    private final EqpParamCommandPort eqpParamCommandPort;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * @param eqpQueryPort 설비 조회 포트
     * @param eqpParamCommandPort 설비 파라미터 커맨드 포트
     */
    public EqpParamController(
            final EqpQueryPort eqpQueryPort,
            final EqpParamCommandPort eqpParamCommandPort
    ) {
        this.eqpQueryPort = Objects.requireNonNull(eqpQueryPort, "eqpQueryPort is null");
        this.eqpParamCommandPort = Objects.requireNonNull(eqpParamCommandPort, "eqpParamCommandPort is null");
    }

    /**
     * 설비의 체크아웃 상태를 조회합니다.
     *
     * <p>EDIT 버전 파라미터 존재 여부와 체크아웃한 사용자 ID를 반환합니다.</p>
     *
     * @param eqpId 설비 비즈니스 ID
     * @return 체크아웃 상태 응답
     */
    @GetMapping("/{eqpId}/checkout-status")
    public ResponseEntity<ApiResponse<EqpCheckoutStatusResponse>> getCheckoutStatus(
            @PathVariable final String eqpId
    ) {
        if (log.isDebugEnabled()) {
            log.debug("설비 체크아웃 상태 조회 요청. eqpId={}", eqpId);
        }

        final Optional<EqpQueryPort.EqpCheckoutStateView> optionalState = eqpQueryPort.findCheckoutStateByEqpId(eqpId);
        if (optionalState.isEmpty()) {
            log.warn("설비 체크아웃 상태 조회 결과 없음 - 설비 미존재. eqpId={}", eqpId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "설비를 찾을 수 없습니다."));
        }

        final EqpQueryPort.EqpCheckoutStateView state = optionalState.get();
        return ResponseEntity.ok(ApiResponse.success(
                new EqpCheckoutStatusResponse(state.checkedOut(), state.checkedOutBy())
        ));
    }

    /**
     * 설비의 특정 버전 파라미터 목록을 조회합니다.
     *
     * @param eqpId 설비 비즈니스 ID
     * @param version 파라미터 버전 (예: "v1.0", "EDIT")
     * @return 파라미터 목록 응답
     */
    @GetMapping("/{eqpId}/params")
    public ResponseEntity<ApiResponse<List<EqpParamResponse>>> getParams(
            @PathVariable final String eqpId,
            @RequestParam(name = "version") final String version
    ) {
        if (log.isDebugEnabled()) {
            log.debug("설비 파라미터 조회 요청. eqpId={}, version={}", eqpId, version);
        }

        final List<TcEqpParam> params = eqpQueryPort.findParamsByEqpIdAndVersion(eqpId, version);
        final List<EqpParamResponse> response = params.stream()
                .map(p -> new EqpParamResponse(p.paramName(), p.paramValue(), p.description(), p.createdBy()))
                .toList();

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 설비 파라미터를 체크아웃합니다.
     *
     * <p>선택 버전의 파라미터를 EDIT 버전으로 복사하여 편집 잠금을 생성합니다.
     * 이미 체크아웃 중이면 409 Conflict를 반환합니다.</p>
     *
     * @param eqpId 설비 비즈니스 ID
     * @param request 체크아웃 요청 (sourceVersion 필수)
     * @param authentication 현재 로그인 사용자 정보
     * @return 생성된 EDIT 파라미터 목록
     */
    @PostMapping("/{eqpId}/checkout")
    public ResponseEntity<ApiResponse<List<EqpParamResponse>>> checkout(
            @PathVariable final String eqpId,
            @Valid @RequestBody final EqpCheckoutRequest request,
            final Authentication authentication
    ) {
        final String currentUser = resolveCurrentUser(authentication);
        log.info("설비 파라미터 체크아웃 요청. eqpId={}, sourceVersion={}, currentUser={}", eqpId, request.sourceVersion(), currentUser);

        try {
            final List<EqpParamCommandPort.EqpParamView> editParams =
                    eqpParamCommandPort.checkout(eqpId, request.sourceVersion(), currentUser);

            final List<EqpParamResponse> response = editParams.stream()
                    .map(p -> new EqpParamResponse(p.paramName(), p.paramValue(), p.description(), p.createdBy()))
                    .toList();

            log.info("설비 파라미터 체크아웃 완료. eqpId={}, paramCount={}", eqpId, response.size());
            return ResponseEntity.ok(ApiResponse.success(response));

        } catch (EqpAlreadyCheckedOutException e) {
            log.warn("설비 파라미터 이미 체크아웃 중. eqpId={}, reason={}", eqpId, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("ALREADY_CHECKED_OUT", e.getMessage()));
        }
    }

    /**
     * EDIT 버전의 파라미터를 저장합니다.
     *
     * <p>현재 EDIT 전체를 요청 목록 기준으로 다시 저장하므로
     * paramName 변경, description 수정, 행 추가/삭제를 한 번에 반영할 수 있습니다.</p>
     *
     * @param eqpId 설비 비즈니스 ID
     * @param request 저장할 파라미터 목록
     * @param authentication 현재 로그인 사용자 정보
     * @return 200 OK
     */
    @PutMapping("/{eqpId}/edit-params")
    public ResponseEntity<ApiResponse<Void>> saveEditParams(
            @PathVariable final String eqpId,
            @Valid @RequestBody final EqpParamSaveRequest request,
            final Authentication authentication
    ) {
        final String currentUser = resolveCurrentUser(authentication);
        log.info("EDIT 파라미터 저장 요청. eqpId={}, paramCount={}, currentUser={}", eqpId, request.params().size(), currentUser);

        final List<EqpParamCommandPort.EqpParamEdit> edits = request.params().stream()
                .map(item -> new EqpParamCommandPort.EqpParamEdit(item.paramName(), item.paramValue(), item.description()))
                .toList();

        eqpParamCommandPort.saveEditParams(eqpId, edits, currentUser);

        log.info("EDIT 파라미터 저장 완료. eqpId={}", eqpId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * 체크아웃을 되돌립니다.
     *
     * <p>현재 설비의 EDIT 버전 파라미터를 모두 삭제하여 편집 잠금을 해제합니다.</p>
     *
     * @param eqpId 설비 비즈니스 ID
     * @param authentication 현재 로그인 사용자 정보
     * @return 200 OK
     */
    @DeleteMapping("/{eqpId}/checkout")
    public ResponseEntity<ApiResponse<Void>> undoCheckout(
            @PathVariable final String eqpId,
            final Authentication authentication
    ) {
        final String currentUser = resolveCurrentUser(authentication);
        log.info("설비 파라미터 체크아웃 되돌리기 요청. eqpId={}, currentUser={}", eqpId, currentUser);

        eqpParamCommandPort.undoCheckout(eqpId, currentUser);

        log.info("설비 파라미터 체크아웃 되돌리기 완료. eqpId={}", eqpId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * 체크인을 수행합니다.
     *
     * <p>EDIT 버전을 신규 버전으로 저장하고 EDIT 버전을 삭제합니다.</p>
     *
     * @param eqpId 설비 비즈니스 ID
     * @param request 체크인 요청 (description 선택)
     * @param authentication 현재 로그인 사용자 정보
     * @return 200 OK
     */
    @PostMapping("/{eqpId}/checkin")
    public ResponseEntity<ApiResponse<Void>> checkin(
            @PathVariable final String eqpId,
            @Valid @RequestBody final EqpCheckinRequest request,
            final Authentication authentication
    ) {
        final String currentUser = resolveCurrentUser(authentication);
        log.info("설비 파라미터 체크인 요청. eqpId={}, currentUser={}", eqpId, currentUser);

        eqpParamCommandPort.checkin(eqpId, request.description(), currentUser);

        log.info("설비 파라미터 체크인 완료. eqpId={}", eqpId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * Spring Security Authentication에서 현재 사용자 ID를 추출합니다.
     *
     * @param authentication 인증 정보
     * @return 사용자 ID (인증 정보가 없으면 "ANONYMOUS")
     */
    private static String resolveCurrentUser(final Authentication authentication) {
        if (authentication == null) {
            return "ANONYMOUS";
        }
        if (authentication.getPrincipal() instanceof UserPrincipal principal
                && principal.userId() != null
                && !principal.userId().isBlank()) {
            return principal.userId();
        }
        if (authentication.getName() == null || authentication.getName().isBlank()) {
            return "ANONYMOUS";
        }
        return authentication.getName();
    }
}
