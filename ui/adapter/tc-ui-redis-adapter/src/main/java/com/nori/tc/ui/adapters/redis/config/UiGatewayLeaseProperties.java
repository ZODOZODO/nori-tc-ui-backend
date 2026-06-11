package com.nori.tc.ui.adapters.redis.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway shard lease 조회용 Redis 키 설정입니다.
 *
 * <p>Gateway가 lease를 기록하는 키는 {@code <key-prefix>:<groupId>} 형식이며,
 * 본 prefix는 Gateway 배포의 {@code tc.gateway.lease.key-prefix}와 반드시 동일해야 합니다.
 * 값이 다르면 lease 조회가 항상 미점유로 보입니다.</p>
 */
@ConfigurationProperties(prefix = "tc.gateway.lease")
public class UiGatewayLeaseProperties {

    private static final Logger log = LoggerFactory.getLogger(UiGatewayLeaseProperties.class);

    /**
     * Redis lease 키 prefix입니다. 최종 키는 {@code <prefix>:<groupId>}로 구성됩니다.
     */
    private String keyPrefix = "tc:gateway:lease";

    @PostConstruct
    public void validate() {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalStateException("tc.gateway.lease.key-prefix 는 비어 있을 수 없습니다.");
        }
        log.info("UiGatewayLeaseProperties 검증 완료. keyPrefix={}", keyPrefix);
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(final String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }
}
