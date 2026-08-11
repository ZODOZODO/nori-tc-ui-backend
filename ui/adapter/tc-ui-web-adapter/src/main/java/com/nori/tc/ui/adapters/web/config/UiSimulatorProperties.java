package com.nori.tc.ui.adapters.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 신규 simulator orchestrator(:18090) 연동 설정(R-1). orchestrator base-url 및 HTTP 타임아웃/reaper 주기를 바인딩한다.
 */
@ConfigurationProperties(prefix = "tc.ui.simulator")
public class UiSimulatorProperties {

    private String baseUrl = "http://127.0.0.1:18090";
    // simulator 제어 표면 공유 시크릿(simulator 감사 S11 E-1 — simulator 측이 fail-closed 로 검증한다).
    // 기본값 없음: 미설정으로 호출하면 simulator 가 401 로 거부한다(조용히 통과하는 경로를 만들지 않는다).
    private String internalToken = "";
    private long connectTimeoutMs = 3000L;
    private long readTimeoutMs = 5000L;
    // createRun 전용 타임아웃 — 조회/stop 과 달리 대상 수에 비례해 워커 inject 를 반복하므로 readTimeoutMs 로는 짧다.
    // 이 타임아웃이 터지면 orchestrator 는 run 을 이미 만든 뒤라 lease 를 문 채 고아가 되고(추적 전 예외라 reaper 도
    // 회수 못 한다) 사람이 stop 하기 전엔 안 풀린다. 그래서 다른 호출보다 넉넉히 둔다.
    private long createRunTimeoutMs = 60_000L;
    private long reaperIntervalMs = 5000L;
    // 연결-후-MES 배리어가 백그라운드(요청 밖)에서 설비 연결을 확인할 때의 타임아웃. 대량 설비(수십~수백)는
    // 동시 연결에 dualRequestTimeoutMs(10s)보다 오래 걸리며, 백그라운드라 외부 HTTP 프록시 압박이 없어 넉넉히 둔다.
    private long connectionConfirmTimeoutMs = 120_000L;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(final String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(final String internalToken) {
        this.internalToken = internalToken;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(final long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(final long readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public long getCreateRunTimeoutMs() {
        return createRunTimeoutMs;
    }

    public void setCreateRunTimeoutMs(final long createRunTimeoutMs) {
        this.createRunTimeoutMs = createRunTimeoutMs;
    }

    public long getReaperIntervalMs() {
        return reaperIntervalMs;
    }

    public void setReaperIntervalMs(final long reaperIntervalMs) {
        this.reaperIntervalMs = reaperIntervalMs;
    }

    public long getConnectionConfirmTimeoutMs() {
        return connectionConfirmTimeoutMs;
    }

    public void setConnectionConfirmTimeoutMs(final long connectionConfirmTimeoutMs) {
        this.connectionConfirmTimeoutMs = connectionConfirmTimeoutMs;
    }
}
