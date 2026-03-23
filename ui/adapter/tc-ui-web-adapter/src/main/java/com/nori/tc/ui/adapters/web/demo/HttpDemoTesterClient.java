package com.nori.tc.ui.adapters.web.demo;

import com.nori.tc.ui.adapters.web.config.UiDemoTesterProperties;
import com.nori.tc.ui.core.exception.UiBadRequestException;
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
 * demo-tester HTTP 호출 구현체입니다.
 */
public class HttpDemoTesterClient implements DemoTesterClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final UiDemoTesterProperties properties;

    public HttpDemoTesterClient(
            final HttpClient httpClient,
            final ObjectMapper objectMapper,
            final UiDemoTesterProperties properties
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
        this.properties = Objects.requireNonNull(properties, "properties is null");
    }

    @Override
    public DemoRunResponse createRun(final DemoTesterCreateRunRequest request) {
        return sendJson("POST", "/api/demo-runs", request, DemoRunResponse.class);
    }

    @Override
    public DemoRunResponse startTarget(final String runId, final String eqpId, final DemoTesterTargetStartRequest request) {
        return sendJson("POST", "/api/demo-runs/" + runId + "/targets/" + eqpId + "/start", request, DemoRunResponse.class);
    }

    @Override
    public DemoRunResponse stopTarget(final String runId, final String eqpId) {
        return sendJson("POST", "/api/demo-runs/" + runId + "/targets/" + eqpId + "/stop", null, DemoRunResponse.class);
    }

    @Override
    public DemoRunResponse stopRun(final String runId) {
        return sendJson("POST", "/api/demo-runs/" + runId + "/stop", null, DemoRunResponse.class);
    }

    @Override
    public DemoRunResponse getRun(final String runId) {
        return sendJson("GET", "/api/demo-runs/" + runId, null, DemoRunResponse.class);
    }

    @Override
    public List<DemoTargetResponse> getTargets(final String runId) {
        return sendJson("GET", "/api/demo-runs/" + runId + "/targets", null, new TypeReference<>() {});
    }

    private <T> T sendJson(final String method, final String path, final Object body, final Class<T> responseType) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getBaseUrl() + path))
                    .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                    .header("Content-Type", "application/json");
            if ("GET".equals(method)) {
                builder.GET();
            } else if (body == null) {
                builder.POST(HttpRequest.BodyPublishers.noBody());
            } else {
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)));
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new UiBadRequestException("demo-tester 호출에 실패했습니다. status=" + response.statusCode());
            }
            return objectMapper.readValue(response.body(), responseType);
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new UiBadRequestException("demo-tester 호출 중 오류가 발생했습니다: " + exception.getMessage(), exception);
        }
    }

    private <T> T sendJson(final String method, final String path, final Object body, final TypeReference<T> responseType) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getBaseUrl() + path))
                    .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                    .header("Content-Type", "application/json");
            if ("GET".equals(method)) {
                builder.GET();
            } else if (body == null) {
                builder.POST(HttpRequest.BodyPublishers.noBody());
            } else {
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)));
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new UiBadRequestException("demo-tester 호출에 실패했습니다. status=" + response.statusCode());
            }
            return objectMapper.readValue(response.body(), responseType);
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new UiBadRequestException("demo-tester 호출 중 오류가 발생했습니다: " + exception.getMessage(), exception);
        }
    }
}
