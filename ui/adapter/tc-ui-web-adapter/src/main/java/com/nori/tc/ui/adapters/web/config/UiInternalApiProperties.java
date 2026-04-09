package com.nori.tc.ui.adapters.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 내부 서비스 간 호출 보호용 설정입니다.
 */
@ConfigurationProperties(prefix = "tc.ui.backend.internal-api")
public class UiInternalApiProperties {

    private String headerName = "X-TC-INTERNAL-TOKEN";
    private String headerValue = "";

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(final String headerName) {
        this.headerName = headerName;
    }

    public String getHeaderValue() {
        return headerValue;
    }

    public void setHeaderValue(final String headerValue) {
        this.headerValue = headerValue;
    }
}
