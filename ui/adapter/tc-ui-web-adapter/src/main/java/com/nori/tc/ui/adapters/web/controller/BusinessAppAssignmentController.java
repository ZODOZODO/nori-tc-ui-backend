package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.BusinessAppAssignmentResponse;
import com.nori.tc.ui.core.port.messaging.BusinessAppAssignmentPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Business 프로세스별 partition 할당 조회 REST API 컨트롤러입니다.
 *
 * <p>EQP 화면이 설비를 business_app 단위로 분류할 때 쓰는 기준 데이터를 제공합니다 — 단위는 consumer
 * group 멤버가 아니라 <b>Pod</b> 입니다(Pod 하나가 토픽마다 멤버를 따로 가짐).
 * {@code /api/eqp} prefix 하위에 두어 기존 {@code EQP_READ}(PREFIX /api/eqp, GET) 권한 시드를
 * 그대로 재사용합니다(seed 변경 불요) — {@link GatewayShardLeaseController} 와 같은 규칙입니다.</p>
 */
@RestController
@RequestMapping("/api/eqp/business-app-assignments")
public class BusinessAppAssignmentController {

    private final BusinessAppAssignmentPort businessAppAssignmentPort;

    public BusinessAppAssignmentController(final BusinessAppAssignmentPort businessAppAssignmentPort) {
        this.businessAppAssignmentPort = Objects.requireNonNull(businessAppAssignmentPort,
                "businessAppAssignmentPort is null");
    }

    /**
     * 살아 있는 Business Pod 과 각각의 담당 partition 을 조회합니다.
     *
     * @return 담당 partition 최솟값 오름차순 목록. Pod 이 없으면 빈 목록.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<BusinessAppAssignmentResponse>>> list() {
        final List<BusinessAppAssignmentResponse> assignments =
                businessAppAssignmentPort.findAllAssignments().stream()
                        .map(assignment -> new BusinessAppAssignmentResponse(
                                assignment.host(),
                                assignment.memberIds(),
                                assignment.ownedPartitions()
                        ))
                        .toList();
        return ResponseEntity.ok(ApiResponse.success(assignments));
    }
}
