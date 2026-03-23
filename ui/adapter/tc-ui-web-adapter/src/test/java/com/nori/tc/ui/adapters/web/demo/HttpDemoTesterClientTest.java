package com.nori.tc.ui.adapters.web.demo;

import com.nori.tc.ui.adapters.web.config.UiDemoTesterProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpDemoTesterClientTest {

    private HttpServer server;
    private HttpDemoTesterClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();

        UiDemoTesterProperties properties = new UiDemoTesterProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        client = new HttpDemoTesterClient(HttpClient.newHttpClient(), new ObjectMapper(), properties);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void createRunPostsToDemoTesterAndParsesResponse() throws Exception {
        server.createContext("/api/demo-runs", exchange -> {
            byte[] responseBody = new ObjectMapper().writeValueAsBytes(new DemoRunResponse(
                    "RUN-01",
                    "RUNNING",
                    10,
                    OffsetDateTime.parse("2026-03-23T16:00:00+09:00"),
                    OffsetDateTime.parse("2026-03-23T16:00:01+09:00"),
                    List.of(new DemoTargetResponse("EQP-01", "EQP-01", List.of("1", "2"), 2, "RUNNING", 1, 0, 1, List.of("1", "2"), Set.of("socket-1")))
            ));
            exchange.sendResponseHeaders(200, responseBody.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(responseBody);
            }
        });

        DemoRunResponse response = client.createRun(new DemoTesterCreateRunRequest(
                "e2e-demo",
                10,
                List.of(new DemoTesterCreateRunRequest.DemoTesterTargetRequest("EQP-01", "EQP-01", List.of("1", "2"), 2))
        ));

        assertEquals("RUN-01", response.runId());
        assertEquals("RUNNING", response.state());
        assertEquals(List.of("1", "2"), response.targets().getFirst().availablePortIds());
    }
}
