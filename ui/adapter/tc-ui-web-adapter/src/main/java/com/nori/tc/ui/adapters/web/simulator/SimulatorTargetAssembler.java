package com.nori.tc.ui.adapters.web.simulator;

import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.service.EqpManagementService;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpPortStatus;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 선택 설비의 실 DB 설정({@code tc_eqp})을 orchestrator createRun target 으로 변환(R-3, C-1: override 발명 0).
 * demo-tester {@code UiEquipmentRuntimeSpecAssembler} 선례(직접 의존 0 — eqp 관리 스냅샷 조회 미러).
 *
 * <p>주소/포트를 발명하거나 eqp_ip 를 임의 override 하지 않는다 — DB 실값 그대로(bindPort=eqp_port). bind/dial
 * 방향은 orchestrator 가 comm_mode 를 반전해 결정(R-4). eqp_ip 는 target 에 싣지 않는다(C1 의 0.0.0.0 wildcard 는
 * dial 대상으로 무용 — dial host 는 시뮬레이터-측 config).
 */
public final class SimulatorTargetAssembler {

    private final EqpManagementService eqpManagementService;

    public SimulatorTargetAssembler(final EqpManagementService eqpManagementService) {
        this.eqpManagementService = Objects.requireNonNull(eqpManagementService, "eqpManagementService is null");
    }

    public SimulatorCreateRunRequest.SimulatorTargetRequest toTarget(final String eqpId) {
        final EqpManagementSnapshot snapshot = eqpManagementService.getManageDetail(eqpId)
                .orElseThrow(() -> new UiNotFoundException("설비를 찾을 수 없습니다. eqpId=" + eqpId));
        final UiEqp eqp = snapshot.eqp();
        return new SimulatorCreateRunRequest.SimulatorTargetRequest(
                eqp.eqpId(),
                eqp.commInterface().name(),
                eqp.commMode(),
                eqp.eqpPort(),
                resolvePortIds(snapshot));
    }

    private List<String> resolvePortIds(final EqpManagementSnapshot snapshot) {
        final List<String> portIds = snapshot.portStatuses().stream()
                .map(UiEqpPortStatus::portId)
                .filter(portId -> portId != null && !portId.isBlank())
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));
        if (portIds.isEmpty()) {
            throw new UiBadRequestException("테스트 가능한 포트가 없습니다. eqpId=" + snapshot.eqp().eqpId());
        }
        return portIds;
    }
}
