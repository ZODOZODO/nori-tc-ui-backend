package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.EqpRepairTaskResponse;
import com.nori.tc.ui.core.port.redis.BusinessDlqPort;
import com.nori.tc.ui.core.port.redis.GatewayDlqPort;
import com.nori.tc.ui.core.service.EqpRepairTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * DLQ(Dead Letter Queue) 관리 REST API 컨트롤러입니다.
 *
 * <p>제공 엔드포인트:</p>
 * <ul>
 *   <li>GET    /api/dlq/gateway          — Gateway DLQ 항목 전체 조회</li>
 *   <li>DELETE /api/dlq/gateway/{dlqId}  — Gateway DLQ 항목 삭제</li>
 *   <li>GET    /api/dlq/business         — Business DLQ 항목 전체 조회</li>
 *   <li>DELETE /api/dlq/business/{dlqId} — Business DLQ 항목 삭제</li>
 *   <li>GET    /api/dlq/quarantine       — Gateway Quarantine(격리 설비) 항목 전체 조회</li>
 *   <li>GET    /api/dlq/repair-task      — EQP 보상 repair task 전체 조회</li>
 * </ul>
 *
 * <p>DLQ 역할:</p>
 * <p>Kafka 메시지 처리 실패 시 Gateway/Business Core가 각각의 Redis에 DLQ 항목을 기록합니다.
 * 운영자는 이 API를 통해 DLQ 현황을 파악하고 문제 해결 후 항목을 수동으로 제거합니다.</p>
 *
 * <p>반환 타입:</p>
 * <p>각 DLQ 항목은 Gateway/Business 어댑터 고유의 직렬화 객체입니다.
 * {@link GatewayDlqPort}와 {@link BusinessDlqPort}는 {@code List<?>}로 반환하며,
 * Jackson이 런타임 타입 기반으로 JSON을 생성합니다.</p>
 */
@RestController
@RequestMapping("/api/dlq")
public class DlqController {

    private static final Logger log = LoggerFactory.getLogger(DlqController.class);

    private final GatewayDlqPort gatewayDlqPort;
    private final BusinessDlqPort businessDlqPort;
    private final EqpRepairTaskService eqpRepairTaskService;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * @param gatewayDlqPort       Gateway DLQ 조회·삭제 포트 (Gateway Redis 6379)
     * @param businessDlqPort      Business DLQ 조회·삭제 포트
     * @param eqpRepairTaskService EQP 보상 repair task 조회 서비스
     */
    public DlqController(
            final GatewayDlqPort gatewayDlqPort,
            final BusinessDlqPort businessDlqPort,
            final EqpRepairTaskService eqpRepairTaskService
    ) {
        this.gatewayDlqPort = Objects.requireNonNull(gatewayDlqPort, "gatewayDlqPort is null");
        this.businessDlqPort = Objects.requireNonNull(businessDlqPort, "businessDlqPort is null");
        this.eqpRepairTaskService = Objects.requireNonNull(eqpRepairTaskService, "eqpRepairTaskService is null");
    }

    // -------------------------------------------------------------------------
    // Gateway DLQ
    // -------------------------------------------------------------------------

    /**
     * Gateway Redis에 저장된 DLQ 항목 전체를 조회합니다.
     *
     * <p>SCAN 커서 방식으로 조회하므로 대용량 데이터셋에서도 Redis를 차단하지 않습니다.
     * 역직렬화 실패 항목은 건너뛰고 성공한 항목만 반환합니다.</p>
     *
     * @return 200 OK + Gateway DLQ 항목 목록 (빈 리스트 가능)
     */
    @GetMapping("/gateway")
    public ResponseEntity<ApiResponse<List<?>>> listGatewayDlq() {
        log.info("Gateway DLQ 목록 조회 요청.");

        final List<?> entries = gatewayDlqPort.listAllDlq();

        log.info("Gateway DLQ 목록 조회 완료. count={}", entries.size());
        return ResponseEntity.ok(ApiResponse.success(entries));
    }

    /**
     * Gateway Redis에서 지정한 dlqId의 DLQ 항목을 삭제합니다.
     *
     * <p>키가 이미 삭제된 경우에도 200을 반환합니다 (멱등성 보장).
     * 삭제 여부는 응답 본문의 {@code data} 필드로 확인합니다.</p>
     *
     * @param dlqId 삭제할 DLQ 항목 ID
     * @return 200 OK + 삭제 성공 여부 (true/false)
     */
    @DeleteMapping("/gateway/{dlqId}")
    public ResponseEntity<ApiResponse<Boolean>> deleteGatewayDlq(
            @PathVariable final String dlqId
    ) {
        log.info("Gateway DLQ 항목 삭제 요청. dlqId={}", dlqId);

        final boolean deleted = gatewayDlqPort.deleteDlq(dlqId);

        if (deleted) {
            log.info("Gateway DLQ 항목 삭제 완료. dlqId={}", dlqId);
        } else {
            log.warn("Gateway DLQ 항목 삭제 대상 없음 (이미 삭제됐거나 존재하지 않음). dlqId={}", dlqId);
        }

        return ResponseEntity.ok(ApiResponse.success(deleted));
    }

    // -------------------------------------------------------------------------
    // Business DLQ
    // -------------------------------------------------------------------------

    /**
     * Business Redis에 저장된 DLQ 항목 전체를 조회합니다.
     *
     * <p>SCAN 커서 방식으로 조회하므로 대용량 데이터셋에서도 Redis를 차단하지 않습니다.
     * 역직렬화 실패 항목은 건너뛰고 성공한 항목만 반환합니다.</p>
     *
     * @return 200 OK + Business DLQ 항목 목록 (빈 리스트 가능)
     */
    @GetMapping("/business")
    public ResponseEntity<ApiResponse<List<?>>> listBusinessDlq() {
        log.info("Business DLQ 목록 조회 요청.");

        final List<?> entries = businessDlqPort.listAllDlq();

        log.info("Business DLQ 목록 조회 완료. count={}", entries.size());
        return ResponseEntity.ok(ApiResponse.success(entries));
    }

    /**
     * Business Redis에서 지정한 dlqId의 DLQ 항목을 삭제합니다.
     *
     * <p>키가 이미 삭제된 경우에도 200을 반환합니다 (멱등성 보장).
     * 삭제 여부는 응답 본문의 {@code data} 필드로 확인합니다.</p>
     *
     * @param dlqId 삭제할 DLQ 항목 ID
     * @return 200 OK + 삭제 성공 여부 (true/false)
     */
    @DeleteMapping("/business/{dlqId}")
    public ResponseEntity<ApiResponse<Boolean>> deleteBusinessDlq(
            @PathVariable final String dlqId
    ) {
        log.info("Business DLQ 항목 삭제 요청. dlqId={}", dlqId);

        final boolean deleted = businessDlqPort.deleteDlq(dlqId);

        if (deleted) {
            log.info("Business DLQ 항목 삭제 완료. dlqId={}", dlqId);
        } else {
            log.warn("Business DLQ 항목 삭제 대상 없음 (이미 삭제됐거나 존재하지 않음). dlqId={}", dlqId);
        }

        return ResponseEntity.ok(ApiResponse.success(deleted));
    }

    // -------------------------------------------------------------------------
    // Gateway Quarantine
    // -------------------------------------------------------------------------

    /**
     * Gateway Redis 에 저장된 Quarantine(격리 설비) 항목 전체를 조회합니다.
     *
     * <p>설비 격리 상태는 Gateway 가 Kafka 처리 단계에서 결정·기록하며,
     * UI 는 본 endpoint 를 통해 격리 현황을 조회합니다.</p>
     *
     * @return 200 OK + Quarantine 항목 목록 (빈 리스트 가능)
     */
    @GetMapping("/quarantine")
    public ResponseEntity<ApiResponse<List<?>>> listGatewayQuarantine() {
        log.info("Gateway Quarantine 목록 조회 요청.");

        final List<?> entries = gatewayDlqPort.listAllQuarantine();

        log.info("Gateway Quarantine 목록 조회 완료. count={}", entries.size());
        return ResponseEntity.ok(ApiResponse.success(entries));
    }

    // -------------------------------------------------------------------------
    // Repair Task
    // -------------------------------------------------------------------------

    /**
     * EQP 보상 실패 후 누적된 repair task 전체를 조회합니다.
     *
     * <p>{@link EqpRepairTaskService#findAll()} 결과를 HTTP DTO 로 매핑하여 반환합니다.
     * 정렬은 저장소(Redis Hash)의 자연 순서이며, 클라이언트가 {@code createdAt} 기준으로
     * 표시 순서를 결정합니다.</p>
     *
     * @return 200 OK + repair task 응답 DTO 목록 (빈 리스트 가능)
     */
    @GetMapping("/repair-task")
    public ResponseEntity<ApiResponse<List<EqpRepairTaskResponse>>> listRepairTasks() {
        log.info("EQP repair task 목록 조회 요청.");

        final List<EqpRepairTaskResponse> responses = eqpRepairTaskService.findAll().stream()
                .map(EqpRepairTaskResponse::from)
                .toList();

        log.info("EQP repair task 목록 조회 완료. count={}", responses.size());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }
}
