package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.RuntimeInstanceResponse;
import com.nori.tc.ui.core.port.redis.RuntimeInstanceQueryPort;
import com.nori.tc.ui.core.port.redis.RuntimeInstanceQueryPort.RuntimeInstanceView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Gateway / Business 런타임 인스턴스(Pod) 조망 REST API 컨트롤러입니다.
 *
 * <p>{@link GatewayShardLeaseController} 가 답하는 것은 "어느 shard group 이 점유되었나"이고, 이
 * 컨트롤러가 답하는 것은 "어느 Pod 이 살아 있나"입니다. shard group 수는 설정값이고 Pod 수는 replica
 * 수라 두 축의 개수가 다릅니다 — lease 만 보면 슬롯을 못 잡은 대기(spare) Pod 이 화면에서 사라집니다.</p>
 *
 * <p>조회 대상 서비스는 경로로 고정합니다 — 서비스명을 문자열로 받으면 임의 key namespace 조회가
 * 됩니다. {@code /api/eqp} prefix 하위에 두어 기존 {@code EQP_READ}(PREFIX /api/eqp, GET) 권한 시드를
 * 그대로 재사용합니다(seed 변경 불요).</p>
 */
@RestController
@RequestMapping("/api/eqp")
public class RuntimeInstanceController {

    private final RuntimeInstanceQueryPort runtimeInstanceQueryPort;

    public RuntimeInstanceController(final RuntimeInstanceQueryPort runtimeInstanceQueryPort) {
        this.runtimeInstanceQueryPort = Objects.requireNonNull(runtimeInstanceQueryPort,
                "runtimeInstanceQueryPort is null");
    }

    /**
     * 살아 있는 Gateway 인스턴스를 조회합니다.
     *
     * @return sessionId 오름차순 목록. 조정 원장을 쓰지 않는 구성이면 빈 목록.
     */
    @GetMapping("/gateway-instances")
    public ResponseEntity<ApiResponse<List<RuntimeInstanceResponse>>> gatewayInstances() {
        return ResponseEntity.ok(ApiResponse.success(
                toResponses(runtimeInstanceQueryPort.findGatewayInstances())));
    }

    /**
     * 살아 있는 Business 인스턴스를 조회합니다.
     *
     * <p>화면은 이 목록의 {@code podIp} 를 consumer group 멤버 host 와 맞춰 business_app 노드에 Pod
     * 이름을 붙입니다 — Kafka 는 멤버의 host(IP)만 알려주기 때문입니다.</p>
     *
     * @return sessionId 오름차순 목록. 조정 원장을 쓰지 않는 구성이면 빈 목록.
     */
    @GetMapping("/business-instances")
    public ResponseEntity<ApiResponse<List<RuntimeInstanceResponse>>> businessInstances() {
        return ResponseEntity.ok(ApiResponse.success(
                toResponses(runtimeInstanceQueryPort.findBusinessInstances())));
    }

    private static List<RuntimeInstanceResponse> toResponses(final List<RuntimeInstanceView> instances) {
        return instances.stream()
                .map(instance -> new RuntimeInstanceResponse(
                        instance.sessionId(),
                        instance.podName(),
                        instance.podIp(),
                        instance.state(),
                        instance.protocolVersion(),
                        instance.registeredAtEpochMs(),
                        instance.updatedAtEpochMs()
                ))
                .toList();
    }
}
