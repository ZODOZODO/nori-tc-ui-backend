package com.nori.tc.ui.adapters.web.simulator;

/**
 * orchestrator {@code POST /api/scenarios} 요청 본문(R-6) — 마크다운 원문. ui-backend 가 multipart 파일을 UTF-8
 * 문자열로 읽어 JSON {@code {sourceText}} 로 orchestrator 에 전달한다.
 */
public record SimulatorUploadScenarioRequest(String sourceText) {
}
