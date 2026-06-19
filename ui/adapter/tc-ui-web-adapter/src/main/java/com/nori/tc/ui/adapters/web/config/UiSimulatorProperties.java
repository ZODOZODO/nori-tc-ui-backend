package com.nori.tc.ui.adapters.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 신규 simulator orchestrator(:18090) 연동 설정(R-1/R-8). demo-tester 와 <b>병행</b>하는 전이용 토글.
 *
 * <p>{@link #enabled} = false(기본)면 모달 Run 경로가 503 → 프론트가 기존 job 경로(demo-tester)로 폴백(롤백 안전망).
 * true 면 신규 orchestrator Run 경로. demo-tester base-url({@code tc.ui.demo-tester.*})은 무손상 보존. 9칸 e2e
 * 전부 PASS 후 M7 cutover 에서 이 토글 + demo-tester 경로를 제거하고 orchestrator 상시(always-on)로 고정한다.
 */
@ConfigurationProperties(prefix = "tc.ui.simulator")
public class UiSimulatorProperties {

    private boolean enabled = false;
    private String baseUrl = "http://127.0.0.1:18090";
    private long connectTimeoutMs = 3000L;
    private long readTimeoutMs = 5000L;
    private long reaperIntervalMs = 5000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

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
}
