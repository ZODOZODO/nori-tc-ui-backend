package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.OperationAdminResponse;
import com.nori.tc.ui.core.coordination.UiOperationAdminQueryService;
import com.nori.tc.ui.core.coordination.model.UiOperationAdminView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Optional;

/**
 * 운영자 관리 조회 REST API 컨트롤러입니다(§20.3).
 *
 * <p>{@code GET /api/async/{id}} 가 Front 진행 표시용 최소 필드만 돌려주는 것과 달리, 여기서는
 * barrier 가 왜 열리지 않았는지를 읽는 데 필요한 원장 내부 상태를 함께 돌려줍니다 — 영향 resource 의
 * committed/open revision, 고정 participant 의 prepare/applied proof, Gateway owner epoch,
 * DB finalization, repair reason 입니다.</p>
 *
 * <p><b>읽기 전용입니다.</b> §20.3 은 강제 PASS / revision 감소 / participant proof 위조 /
 * barrier 무조건 open 같은 관리 동작을 명시적으로 금지합니다 — 그런 수동 복구는 별도 승인·감사 계약이
 * 필요하므로 이 컨트롤러에는 쓰기 매핑을 두지 않습니다.</p>
 *
 * <p>접근 통제는 기존 API permission catalog 가 맡습니다. {@code COORDINATION_ADMIN_READ} permCode 가
 * {@code /api/coordination/operations} prefix + {@code GET} 으로 등록되어 있고 ADMIN 그룹에만
 * grant 됩니다(Flyway V8). 이 백엔드는 closed-by-default 라 grant 가 없으면 admin 이라도 차단됩니다.</p>
 *
 * <p>phase 전이 이력도 함께 돌려줍니다. 이력은 별도 key 가 아니라 operation hash 의
 * {@code phaseHistory} field 에 쌓이며, 기록 상한에 닿으면 뒤쪽이 잘립니다 —
 * {@code OperationLedgerRedisStore#readPhaseHistory} 참조.</p>
 */
@RestController
@RequestMapping("/api/coordination/operations")
public class CoordinationAdminController {

    private static final Logger log = LoggerFactory.getLogger(CoordinationAdminController.class);

    private final ObjectProvider<UiOperationAdminQueryService> adminQueryServiceProvider;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * <p>조회 서비스를 {@link ObjectProvider} 로 받습니다 — 그 빈은 조정 원장이 켜진 구성에만
     * 존재하는데, 컨트롤러가 직접 주입받으면 <b>조정 비활성 구성에서 앱이 기동하지 못합니다</b>.
     * 같은 이유로 {@code UiCommandIngressService} 도 reply proof 서비스를 이 방식으로 받습니다.</p>
     *
     * @param adminQueryServiceProvider 관리 조회 서비스 공급자(비조정 구성이면 비어 있음)
     */
    public CoordinationAdminController(
            final ObjectProvider<UiOperationAdminQueryService> adminQueryServiceProvider
    ) {
        this.adminQueryServiceProvider =
                Objects.requireNonNull(adminQueryServiceProvider, "adminQueryServiceProvider is null");
    }

    /**
     * operation 의 관리 조회 스냅샷을 반환합니다.
     *
     * <p>비terminal 이어도 {@code 200} 입니다 — 이 API 는 진행 폴링이 아니라 상태 관측이 목적이고,
     * {@code 202} 로 돌려주면 운영 도구가 "아직 결과 없음" 으로 오해합니다.</p>
     *
     * <p>조정 원장 조회 불가는 {@code 503} 으로 나갑니다({@code UiApiExceptionHandler}) —
     * 404 로 환원하면 운영자가 "operation 이 사라졌다" 로 읽습니다.</p>
     *
     * @param operationId 대상 operation
     * @return 200(존재) / 404(미존재)
     */
    @GetMapping("/{operationId}")
    public ResponseEntity<ApiResponse<OperationAdminResponse>> findOperation(
            @PathVariable final String operationId
    ) {
        final UiOperationAdminQueryService adminQueryService = adminQueryServiceProvider.getIfAvailable();
        if (adminQueryService == null) {
            // 조정 원장을 쓰지 않는 구성이라 관측할 operation 자체가 없다. 404 로 답하면 "그 id 만 없다"
            // 로 읽히므로 구성 상태임을 코드로 구분한다.
            log.warn("조정 원장이 비활성인 구성에서 관리 조회가 요청됐습니다. operationId={}", operationId);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("COORDINATION_DISABLED", "이 구성은 조정 원장을 사용하지 않습니다."));
        }
        final Optional<UiOperationAdminView> view = adminQueryService.find(operationId);
        if (view.isEmpty()) {
            log.info("조정 관리 조회 대상 없음. operationId={}", operationId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("OPERATION_NOT_FOUND", "operation 을 찾을 수 없습니다. id=" + operationId));
        }
        return ResponseEntity.ok(ApiResponse.success(OperationAdminResponse.from(view.get())));
    }
}
