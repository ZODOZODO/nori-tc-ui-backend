package com.nori.tc.ui.adapters.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 신규 simulator orchestrator(:18090) 연동 설정(R-1). orchestrator base-url 및 HTTP 타임아웃/reaper 주기를 바인딩한다.
 */
@ConfigurationProperties(prefix = "tc.ui.simulator")
public class UiSimulatorProperties {

    private String baseUrl = "http://127.0.0.1:18090";
    private long connectTimeoutMs = 3000L;
    private long readTimeoutMs = 5000L;
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
