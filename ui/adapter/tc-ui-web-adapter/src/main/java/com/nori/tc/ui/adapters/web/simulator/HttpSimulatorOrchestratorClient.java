package com.nori.tc.ui.adapters.web.simulator;

import com.nori.tc.ui.adapters.web.config.UiSimulatorProperties;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 신규 simulator orchestrator(:18090) HTTP 호출 구현(R-1).
 * {@code java.net.http} + sendJson 패턴. 409(lease 충돌)는 {@link UiConflictException}(409)로 매핑 — 충돌
 * 설비 본문을 메시지에 보존(Plan §2-B U1).
 */
public class HttpSimulatorOrchestratorClient implements SimulatorOrchestratorClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final UiSimulatorProperties properties;

    public HttpSimulatorOrchestratorClient(
            final HttpClient httpClient,
            final ObjectMapper objectMapper,
            final UiSimulatorProperties properties
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
        this.properties = Objects.requireNonNull(properties, "properties is null");
    }

    @Override
    public SimulatorRunResponse createRun(final SimulatorCreateRunRequest request) {
        return sendJson("POST", "/api/demo-runs", request, SimulatorRunResponse.class);
    }

    @Override
    public SimulatorRunResponse getRun(final String runId) {
        return sendJson("GET", "/api/demo-runs/" + runId, null, SimulatorRunResponse.class);
    }

    @Override
    public SimulatorRunResponse stopRun(final String runId) {
        return sendJson("POST", "/api/demo-runs/" + runId + "/stop", null, SimulatorRunResponse.class);
    }

    @Override
    public SimulatorRunResponse stopTarget(final String runId, final String eqpId) {
        return sendJson("POST", "/api/demo-runs/" + runId + "/targets/" + eqpId + "/stop", null, SimulatorRunResponse.class);
    }

    @Override
    public SimulatorRunResponse activateUpstream(final String runId) {
        return sendJson("POST", "/api/demo-runs/" + runId + "/activate-upstream", null, SimulatorRunResponse.class);
    }

    @Override
    public List<SimulatorScenarioCatalogItem> getScenarios(final String mode) {
        final String path = mode == null || mode.isBlank()
                ? "/api/scenarios"
                : "/api/scenarios?mode=" + mode;
        return sendJson("GET", path, null, new TypeReference<>() {});
    }

    @Override
    public SimulatorScenarioCatalogItem uploadScenario(final String sourceText) {
        // 409(중복)=UiConflictException / 400(검증 실패)=UiBadRequestException 은 validateResponseStatus 공통 처리.
        return sendJson("POST", "/api/scenarios",
                new SimulatorUploadScenarioRequest(sourceText), SimulatorScenarioCatalogItem.class);
    }

    @Override
    public void deleteScenario(final String scenarioId) {
        try {
            exchange("DELETE", "/api/scenarios/" + scenarioId, null);
        } catch (final SimulatorRunNotFoundException notRun) {
            // 공통 404 핸들러는 Run 전용(SimulatorRunNotFoundException). 시나리오 404 는 시나리오 의미의 UiNotFoundException 으로 분기.
            throw new UiNotFoundException("시나리오를 찾을 수 없습니다. scenarioId=" + scenarioId);
        }
    }

    private <T> T sendJson(final String method, final String path, final Object body, final Class<T> responseType) {
        final HttpResponse<String> response = exchange(method, path, body);
        return objectMapper.readValue(response.body(), responseType);
    }

    private <T> T sendJson(final String method, final String path, final Object body, final TypeReference<T> responseType) {
        final HttpResponse<String> response = exchange(method, path, body);
        return objectMapper.readValue(response.body(), responseType);
    }

    private HttpResponse<String> exchange(final String method, final String path, final Object body) {
        try {
            final HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getBaseUrl() + path))
                    .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                    .header("Content-Type", "application/json");
            if ("GET".equals(method)) {
                builder.GET();
            } else if (body == null) {
                // 본문 없는 메서드(POST stop / DELETE) — 실제 메서드를 보존한다(과거엔 DELETE 가 POST 로 잘못 전송됐다).
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)));
            }
            final HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            validateResponseStatus(response.statusCode(), method, path, response.body());
            return response;
        } catch (final IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new UiBadRequestException("simulator orchestrator 호출 중 오류가 발생했습니다: " + exception.getMessage(), exception);
        }
    }

    private static void validateResponseStatus(final int statusCode, final String method, final String path, final String responseBody) {
        if (statusCode >= 500) {
            throw new IllegalStateException("simulator orchestrator 서버 오류. method=" + method + ", path=" + path + ", status=" + statusCode);
        }
        if (statusCode == 409) {
            // lease 충돌(다른 Run 이 설비 점유) — 충돌 본문(rejected[eqpId, holdingRunId]) 보존.
            throw new UiConflictException("설비가 이미 다른 Run 에 점유되어 있습니다. " + responseBody);
        }
        if (statusCode == 404) {
            // run 미존재 — reaper 가 "종료로 간주"하도록 IO/일반4xx 와 분리된 distinct 신호로 던진다.
            throw new SimulatorRunNotFoundException(
                    "simulator orchestrator Run 미존재. method=" + method + ", path=" + path);
        }
        if (statusCode >= 400) {
            throw new UiBadRequestException("simulator orchestrator 호출에 실패했습니다. status=" + statusCode);
        }
    }
}
