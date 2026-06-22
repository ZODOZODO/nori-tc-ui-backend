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

    /** {@code POST /api/demo-runs/{runId}/targets/{eqpId}/stop} — 설비-단위 중단(다른 target 은 계속). */
    SimulatorRunResponse stopTarget(String runId, String eqpId);

    /**
     * {@code POST /api/demo-runs/{runId}/activate-upstream} — ALL 모드 연결-후-MES 배리어 2단계. 설비 연결 확인 후
     * 호출하면 createRun 에서 보류한 업스트림(MES)을 inject. 보류분 없으면 no-op(멱등).
     */
    SimulatorRunResponse activateUpstream(String runId);

    /** {@code GET /api/scenarios} — 시나리오 카탈로그(R-5). mode blank 면 전체. */
    List<SimulatorScenarioCatalogItem> getScenarios(String mode);

    /**
     * {@code POST /api/scenarios} — 마크다운 원문 업로드(R-6). 검증 실패=400→UiBadRequestException,
     * scenario_id 중복=409→UiConflictException. 성공 시 생성된 카탈로그 항목 반환.
     */
    SimulatorScenarioCatalogItem uploadScenario(String sourceText);

    /**
     * {@code DELETE /api/scenarios/{scenarioId}} — 시나리오 삭제(R-6). 미존재=404→UiNotFoundException,
     * 시드 보호=409→UiConflictException.
     */
    void deleteScenario(String scenarioId);
}
