package com.nori.tc.ui.adapters.web.config;

import com.nori.tc.ui.adapters.web.demo.DemoTesterClient;
import com.nori.tc.ui.adapters.web.demo.HttpDemoTesterClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(UiDemoTesterProperties.class)
public class UiDemoTesterConfiguration {

    @Bean
    public HttpClient demoTesterHttpClient(final UiDemoTesterProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
    }

    @Bean
    public DemoTesterClient demoTesterClient(
            final HttpClient httpClient,
            final ObjectMapper objectMapper,
            final UiDemoTesterProperties properties
    ) {
        return new HttpDemoTesterClient(httpClient, objectMapper, properties);
    }
}
