package com.nori.tc.ui.adapters.web.simulator;

import java.util.List;

/**
 * 신규 simulator orchestrator(:18090) REST 클라이언트(R-1). demo-tester {@code DemoTesterClient} 와 형상이 달라
 * (orchestrator Run 모델 = createRun 1 회 전 target 원자 lease) 신규 인터페이스. ui-front/ui-backend 는 시뮬레이터를
 * REST 계약으로만 소비(코드 의존 0).
 */
public interface SimulatorOrchestratorClient {

    /** {@code POST /api/demo-runs} — Run 생성(전 target 원자 lease). 409=lease 충돌. */
    SimulatorRunResponse createRun(SimulatorCreateRunRequest request);

    /** {@code GET /api/demo-runs/{runId}} — Run 상태 폴링(진행/종료). */
    SimulatorRunResponse getRun(String runId);

    /** {@code POST /api/demo-runs/{runId}/stop} — Run 중단(in-flight lot abort). */
    SimulatorRunResponse stopRun(String runId);

    /** {@code GET /api/scenarios} — 시나리오 카탈로그(R-5). mode blank 면 전체. */
    List<SimulatorScenarioCatalogItem> getScenarios(String mode);
}
