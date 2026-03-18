package com.nori.tc.ui.adapters.kafka.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * UI Kafka 발행 정책 프로퍼티입니다.
 *
 * <p>prefix: {@code tc.ui.backend.kafka}</p>
 * <ul>
 *   <li>{@code publish-timeout-seconds}: 브로커 응답 대기 최대 시간(초)</li>
 *   <li>{@code max-request-bytes}: UI 메시지 발행 사전 가드레일(바이트)</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "tc.ui.backend.kafka")
public class UiKafkaPublishProperties {

    private static final Logger log = LoggerFactory.getLogger(UiKafkaPublishProperties.class);

    /**
     * Kafka 발행 동기 대기 타임아웃(초)입니다.
     *
     * <p>기본값은 3초이며, 0 이하 값은 허용하지 않습니다.</p>
     */
    private long publishTimeoutSeconds = 3L;

    /**
     * UI 메시지 발행 사전 가드레일 바이트 크기입니다.
     *
     * <p>Kafka producer의 {@code max.request.size}와 동일하거나 더 작은 값으로 맞추는 것을 권장합니다.
     * 기본값은 1MB(1,048,576 bytes)입니다.</p>
     */
    private int maxRequestBytes = 1_048_576;

    /**
     * 기동 시 프로퍼티 유효성을 검증합니다.
     */
    @PostConstruct
    public void validate() {
        if (publishTimeoutSeconds <= 0L) {
            throw new IllegalStateException(
                    "tc.ui.backend.kafka.publish-timeout-seconds 는 1 이상이어야 합니다. actual=" + publishTimeoutSeconds);
        }
        if (maxRequestBytes <= 0) {
            throw new IllegalStateException(
                    "tc.ui.backend.kafka.max-request-bytes 는 1 이상이어야 합니다. actual=" + maxRequestBytes
            );
        }
        log.info(
                "UiKafkaPublishProperties 검증 완료. publishTimeoutSeconds={}, maxRequestBytes={}",
                publishTimeoutSeconds,
                maxRequestBytes
        );
    }

    public long getPublishTimeoutSeconds() {
        return publishTimeoutSeconds;
    }

    public void setPublishTimeoutSeconds(final long publishTimeoutSeconds) {
        this.publishTimeoutSeconds = publishTimeoutSeconds;
    }

    public int getMaxRequestBytes() {
        return maxRequestBytes;
    }

    public void setMaxRequestBytes(final int maxRequestBytes) {
        this.maxRequestBytes = maxRequestBytes;
    }
}
