package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.GatewayShardLeaseResponse;
import com.nori.tc.ui.core.port.redis.GatewayShardLeasePort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Gateway shard lease 점유 현황 REST API 컨트롤러입니다.
 *
 * <p>EQP 화면이 설비를 gateway 단위로 분류할 때 사용하는 기준 데이터를 제공합니다.
 * {@code /api/eqp} prefix 하위에 두어 기존 {@code EQP_READ}(PREFIX /api/eqp, GET) 권한
 * 시드를 그대로 재사용합니다(seed 변경 불요).</p>
 */
@RestController
@RequestMapping("/api/eqp/gateway-shard-leases")
public class GatewayShardLeaseController {

    private final GatewayShardLeasePort gatewayShardLeasePort;

    public GatewayShardLeaseController(final GatewayShardLeasePort gatewayShardLeasePort) {
        this.gatewayShardLeasePort = Objects.requireNonNull(gatewayShardLeasePort,
                "gatewayShardLeasePort is null");
    }

    /**
     * 설정된 모든 shard-group의 lease 점유 현황을 조회합니다.
     *
     * @return shard-group 정의 순서를 보존한 lease 현황 목록.
     *         shard-group 매핑이 비어 있으면(단일 인스턴스 호환 모드) 빈 목록.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<GatewayShardLeaseResponse>>> list() {
        final List<GatewayShardLeaseResponse> leases = gatewayShardLeasePort.findAllLeases().stream()
                .map(lease -> new GatewayShardLeaseResponse(
                        lease.groupId(),
                        lease.ownedPartitions(),
                        lease.ownerInstanceId()
                ))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(leases));
    }
}
