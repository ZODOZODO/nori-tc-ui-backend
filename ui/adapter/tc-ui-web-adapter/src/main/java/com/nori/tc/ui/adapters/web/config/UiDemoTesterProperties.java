package com.nori.tc.ui.adapters.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * demo-tester 연동 설정입니다.
 */
@ConfigurationProperties(prefix = "tc.ui.demo-tester")
public class UiDemoTesterProperties {

    private String baseUrl = "http://127.0.0.1:18080";
    private long connectTimeoutMs = 3000L;
    private long readTimeoutMs = 5000L;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(long readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
