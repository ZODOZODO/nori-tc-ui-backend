package com.nori.tc.ui.adapters.web.simulator;

import com.nori.tc.ui.adapters.web.config.UiSimulatorProperties;

import java.util.List;
import java.util.Objects;

/**
 * 모달 Run 경로 facade(R-1/R-2/R-3) — testType→mode 매핑 + 선택 설비 DB 설정→target 변환 + orchestrator createRun.
 * {@link #isEnabled()} 토글(전이용 안전망)로 controller 가 enabled=false 면 503 폴백(프론트 job 경로). 9칸 e2e PASS
 * 후 M7 cutover 에서 토글 제거 + always-on.
 */
public final class LiveMapSimulatorRunFacade {

    private final UiSimulatorProperties properties;
    private final SimulatorOrchestratorClient client;
    private final SimulatorModeMapper modeMapper;
    private final SimulatorTargetAssembler targetAssembler;

    public LiveMapSimulatorRunFacade(
            final UiSimulatorProperties properties,
            final SimulatorOrchestratorClient client,
            final SimulatorModeMapper modeMapper,
            final SimulatorTargetAssembler targetAssembler
    ) {
        this.properties = Objects.requireNonNull(properties, "properties is null");
        this.client = Objects.requireNonNull(client, "client is null");
        this.modeMapper = Objects.requireNonNull(modeMapper, "modeMapper is null");
        this.targetAssembler = Objects.requireNonNull(targetAssembler, "targetAssembler is null");
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /** 모달 시작 — testType→mode + 선택 설비별 DB 설정→target → orchestrator createRun(Run 경로, demo-tester job 경로 아님). */
    public SimulatorRunResponse startRun(final String testType, final String scenarioId, final List<String> eqpIds) {
        final String mode = modeMapper.toMode(testType);
        final List<SimulatorCreateRunRequest.SimulatorTargetRequest> targets = eqpIds.stream()
                .map(targetAssembler::toTarget)
                .toList();
        return client.createRun(new SimulatorCreateRunRequest(mode, scenarioId, targets));
    }

    public SimulatorRunResponse getRun(final String runId) {
        return client.getRun(runId);
    }

    public SimulatorRunResponse stopRun(final String runId) {
        return client.stopRun(runId);
    }

    public List<SimulatorScenarioCatalogItem> getScenarios(final String mode) {
        return client.getScenarios(mode);
    }
}
