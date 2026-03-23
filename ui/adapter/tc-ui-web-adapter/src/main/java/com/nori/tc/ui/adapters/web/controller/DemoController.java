package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.db.domain.eqp.TcEqpPortStatus;
import com.nori.tc.ui.adapters.web.demo.DemoRunResponse;
import com.nori.tc.ui.adapters.web.demo.DemoTargetResponse;
import com.nori.tc.ui.adapters.web.demo.DemoTesterClient;
import com.nori.tc.ui.adapters.web.demo.DemoTesterCreateRunRequest;
import com.nori.tc.ui.adapters.web.demo.DemoTesterTargetStartRequest;
import com.nori.tc.ui.adapters.web.dto.request.DemoRunCreateRequest;
import com.nori.tc.ui.adapters.web.dto.request.DemoTargetStartRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.service.EqpManagementService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/demo-runs")
public class DemoController {

    private final DemoTesterClient demoTesterClient;
    private final EqpManagementService eqpManagementService;

    public DemoController(
            final DemoTesterClient demoTesterClient,
            final EqpManagementService eqpManagementService
    ) {
        this.demoTesterClient = Objects.requireNonNull(demoTesterClient, "demoTesterClient is null");
        this.eqpManagementService = Objects.requireNonNull(eqpManagementService, "eqpManagementService is null");
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DemoRunResponse>> createRun(
            @Valid @RequestBody final DemoRunCreateRequest request
    ) {
        final List<DemoTesterCreateRunRequest.DemoTesterTargetRequest> targets = request.targets().stream()
                .map(target -> {
                    final EqpManagementSnapshot snapshot = resolveSnapshot(target.eqpId());
                    return new DemoTesterCreateRunRequest.DemoTesterTargetRequest(
                            target.eqpId(),
                            snapshot.eqp().eqpId(),
                            resolveAvailablePortIds(snapshot),
                            target.repeatCount()
                    );
                })
                .toList();

        final DemoRunResponse response = demoTesterClient.createRun(
                new DemoTesterCreateRunRequest(request.planId(), request.defaultRepeatCount(), targets)
        );
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{runId}/targets/{eqpId}/start")
    public ResponseEntity<ApiResponse<DemoRunResponse>> startTarget(
            @PathVariable final String runId,
            @PathVariable final String eqpId,
            @Valid @RequestBody final DemoTargetStartRequest request
    ) {
        final EqpManagementSnapshot snapshot = resolveSnapshot(eqpId);
        final DemoRunResponse response = demoTesterClient.startTarget(
                runId,
                eqpId,
                new DemoTesterTargetStartRequest(snapshot.eqp().eqpId(), resolveAvailablePortIds(snapshot), request.repeatCount())
        );
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{runId}/targets/{eqpId}/stop")
    public ResponseEntity<ApiResponse<DemoRunResponse>> stopTarget(
            @PathVariable final String runId,
            @PathVariable final String eqpId
    ) {
        return ResponseEntity.ok(ApiResponse.success(demoTesterClient.stopTarget(runId, eqpId)));
    }

    @PostMapping("/{runId}/stop")
    public ResponseEntity<ApiResponse<DemoRunResponse>> stopRun(
            @PathVariable final String runId
    ) {
        return ResponseEntity.ok(ApiResponse.success(demoTesterClient.stopRun(runId)));
    }

    @GetMapping("/{runId}")
    public ResponseEntity<ApiResponse<DemoRunResponse>> getRun(
            @PathVariable final String runId
    ) {
        return ResponseEntity.ok(ApiResponse.success(demoTesterClient.getRun(runId)));
    }

    @GetMapping("/{runId}/targets")
    public ResponseEntity<ApiResponse<List<DemoTargetResponse>>> getTargets(
            @PathVariable final String runId
    ) {
        return ResponseEntity.ok(ApiResponse.success(demoTesterClient.getTargets(runId)));
    }

    private EqpManagementSnapshot resolveSnapshot(final String eqpId) {
        return eqpManagementService.getManageDetail(eqpId)
                .orElseThrow(() -> new UiNotFoundException("설비를 찾을 수 없습니다. eqpId=" + eqpId));
    }

    private List<String> resolveAvailablePortIds(final EqpManagementSnapshot snapshot) {
        final List<String> portIds = snapshot.portStatuses().stream()
                .map(TcEqpPortStatus::portId)
                .filter(portId -> portId != null && !portId.isBlank())
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf
                ));
        if (portIds.isEmpty()) {
            throw new UiBadRequestException("테스트 가능한 포트가 없습니다. eqpId=" + snapshot.eqp().eqpId());
        }
        return portIds;
    }
}
