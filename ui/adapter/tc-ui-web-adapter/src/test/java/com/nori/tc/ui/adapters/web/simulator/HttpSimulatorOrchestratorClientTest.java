package com.nori.tc.ui.adapters.web.simulator;

import com.nori.tc.ui.adapters.web.config.UiSimulatorProperties;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AC-2 [R-1 Run 경로] wire — createRun 이 신규 orchestrator {@code POST /api/demo-runs} 로 mode/scenarioId/targets
 * 를 정확히 싣는지 embedded HTTP 서버로 검증(요청 본문 캡처). getScenarios 는 {@code GET /api/scenarios}.
 *
 * <p>mock 의의: 실 orchestrator 와 동일 경로/메서드/본문 형상을 캡처해, 잘못된 경로(job 경로 등)나 누락 필드를
 * 재현하면 FAIL 한다(무력 mock 아님).
 */
class HttpSimulatorOrchestratorClientTest {

    private HttpServer server;
    private HttpSimulatorOrchestratorClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        final UiSimulatorProperties properties = new UiSimulatorProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        client = new HttpSimulatorOrchestratorClient(HttpClient.newHttpClient(), mapper, properties);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void createRunPostsToRunPathWithModeScenarioAndTargets() throws Exception {
        final AtomicReference<String> capturedMethod = new AtomicReference<>();
        final AtomicReference<String> capturedBody = new AtomicReference<>();
        server.createContext("/api/demo-runs", exchange -> {
            capturedMethod.set(exchange.getRequestMethod());
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            final byte[] body = mapper.writeValueAsBytes(
                    new SimulatorRunResponse("run-abc", "ALL", "RUNNING", List.of()));
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        final SimulatorRunResponse response = client.createRun(new SimulatorCreateRunRequest(
                "ALL", "modbus-tcp-normal-flow",
                List.of(new SimulatorCreateRunRequest.SimulatorTargetRequest(
                        "PLC_MBT_001", "PLC", "ACTIVE", 7101, List.of("1")))));

        assertEquals("run-abc", response.runId(), "응답 파싱");
        assertEquals("RUNNING", response.state());

        // wire 검증 — Run 경로(POST /api/demo-runs) + 본문 mode/scenarioId/targets.
        assertEquals("POST", capturedMethod.get(), "Run 경로는 POST");
        final SimulatorCreateRunRequest sent = mapper.readValue(capturedBody.get(), SimulatorCreateRunRequest.class);
        assertEquals("ALL", sent.mode(), "wire mode");
        assertEquals("modbus-tcp-normal-flow", sent.scenarioId(), "wire scenarioId");
        assertEquals(1, sent.targets().size(), "wire targets 1건");
        assertEquals("PLC_MBT_001", sent.targets().get(0).eqpId());
        assertEquals("PLC", sent.targets().get(0).commInterface(), "wire commInterface");
        assertEquals("ACTIVE", sent.targets().get(0).commMode(), "wire commMode");
        assertEquals(7101, sent.targets().get(0).bindPort(), "wire bindPort(eqp_port)");
    }

    @Test
    void getScenariosGetsCatalogPath() throws Exception {
        final AtomicReference<String> capturedMethod = new AtomicReference<>();
        server.createContext("/api/scenarios", exchange -> {
            capturedMethod.set(exchange.getRequestMethod());
            final byte[] body = mapper.writeValueAsBytes(List.of(
                    new SimulatorScenarioCatalogItem("normal-flow", "Normal Flow", 1, "SOCKET", List.of("ALL"))));
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        final List<SimulatorScenarioCatalogItem> items = client.getScenarios(null);

        assertEquals("GET", capturedMethod.get(), "카탈로그는 GET /api/scenarios");
        assertEquals(1, items.size());
        assertEquals("normal-flow", items.get(0).scenarioId());
        assertEquals("SOCKET", items.get(0).commInterface());
    }

    @Test
    void getRunThrowsRunNotFoundOn404() {
        respondWithStatus("/api/demo-runs/RUN-404", 404);

        assertThrows(SimulatorRunNotFoundException.class, () -> client.getRun("RUN-404"));
    }

    @Test
    void getRunThrowsIllegalStateOn5xx() {
        respondWithStatus("/api/demo-runs/RUN-500", 500);

        assertThrows(IllegalStateException.class, () -> client.getRun("RUN-500"));
    }

    @Test
    void getRunThrowsConflictOn409() {
        respondWithStatus("/api/demo-runs/RUN-409", 409);

        assertThrows(UiConflictException.class, () -> client.getRun("RUN-409"));
    }

    @Test
    void getRunThrowsBadRequestOnOther4xx() {
        respondWithStatus("/api/demo-runs/RUN-400", 400);

        assertThrows(UiBadRequestException.class, () -> client.getRun("RUN-400"));
    }

    /** 지정 경로에 주어진 status 로 빈 JSON 본문을 반환하는 컨텍스트를 등록한다. */
    private void respondWithStatus(final String path, final int statusCode) {
        server.createContext(path, exchange -> {
            final byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
    }
}
