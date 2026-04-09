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
import static org.junit.jupiter.api.Assertions.assertNull;

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

    @Test
    void createJobsParsesExtendedJobSummaryFields() throws Exception {
        server.createContext("/api/demo-jobs", exchange -> {
            byte[] responseBody = new ObjectMapper().writeValueAsBytes(new DemoJobCreateResponse(
                    List.of(new DemoJobSummaryResponse(
                            "DJOB-20260408-0001",
                            "CMP_DEMO_01",
                            "CMP_DEMO_01",
                            "CMP",
                            "1",
                            "E2E",
                            "normal-flow-mock",
                            1,
                            "Normal Flow Mock",
                            "RUNNING",
                            "RUNNING",
                            "테스트 실행",
                            14,
                            2,
                            14,
                            "CHANNEL_ACTIVE",
                            OffsetDateTime.parse("2026-04-08T10:00:00+09:00"),
                            OffsetDateTime.parse("2026-04-08T10:00:05+09:00"),
                            null,
                            OffsetDateTime.parse("2026-04-08T10:00:06+09:00"),
                            null,
                            null
                    )),
                    List.of()
            ));
            exchange.sendResponseHeaders(200, responseBody.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(responseBody);
            }
        });

        DemoJobCreateResponse response = client.createJobs(new DemoTesterCreateJobsRequest(
                "E2E",
                "normal-flow-mock",
                1,
                List.of("CMP_DEMO_01"),
                "TESTER"
        ));

        DemoJobSummaryResponse acceptedJob = response.acceptedJobs().getFirst();
        assertEquals("DJOB-20260408-0001", acceptedJob.jobId());
        assertEquals(14, acceptedJob.progressPercent());
        assertEquals(2, acceptedJob.currentStepCount());
        assertEquals(14, acceptedJob.totalStepCount());
        assertEquals("2026-04-08T10:00:06+09:00", acceptedJob.updatedAt().toString());
        assertNull(acceptedJob.failureCode());
        assertNull(acceptedJob.failureMessage());
    }

    @Test
    void getJobsParsesExtendedJobSummaryFields() throws Exception {
        server.createContext("/api/demo-jobs", exchange -> {
            byte[] responseBody = new ObjectMapper().writeValueAsBytes(List.of(new DemoJobSummaryResponse(
                    "DJOB-20260408-0002",
                    "CMP_DEMO_01",
                    "CMP_DEMO_01",
                    "CMP",
                    "1",
                    "E2E",
                    "normal-flow-mock",
                    1,
                    "Normal Flow Mock",
                    "FAILED",
                    "FAILED",
                    "실패",
                    14,
                    2,
                    14,
                    "CHANNEL_CLOSED",
                    OffsetDateTime.parse("2026-04-08T10:00:00+09:00"),
                    OffsetDateTime.parse("2026-04-08T10:00:05+09:00"),
                    OffsetDateTime.parse("2026-04-08T10:00:20+09:00"),
                    OffsetDateTime.parse("2026-04-08T10:00:20+09:00"),
                    "CHANNEL_CLOSED",
                    "채널이 닫혔습니다."
            )));
            exchange.sendResponseHeaders(200, responseBody.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(responseBody);
            }
        });

        List<DemoJobSummaryResponse> jobs = client.getJobs("FAILED", 10);

        DemoJobSummaryResponse job = jobs.getFirst();
        assertEquals("FAILED", job.state());
        assertEquals(2, job.currentStepCount());
        assertEquals(14, job.totalStepCount());
        assertEquals("CHANNEL_CLOSED", job.failureCode());
        assertEquals("채널이 닫혔습니다.", job.failureMessage());
    }
}
