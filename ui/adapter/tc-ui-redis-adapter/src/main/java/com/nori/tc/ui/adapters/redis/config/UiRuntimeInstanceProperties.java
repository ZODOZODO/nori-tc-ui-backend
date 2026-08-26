package com.nori.tc.ui.adapters.redis.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 런타임 인스턴스 조망 조회 설정입니다.
 *
 * <p>조회 대상 서비스명은 여기서 다시 정의하지 않습니다 — {@code UiCoordinationProperties} 가 이미
 * {@code gatewayServiceName} / {@code businessServiceName} 을 갖고 있고 operation 발행 경로가 그것을
 * 씁니다. 같은 사실을 두 prefix 로 두면 한쪽만 바꿨을 때 조회가 조용히 0 건이 됩니다.</p>
 *
 * <p>바인딩 prefix: {@code tc.ui.backend.runtime-instance}</p>
 */
@ConfigurationProperties(prefix = "tc.ui.backend.runtime-instance")
public class UiRuntimeInstanceProperties {

    private static final Logger log = LoggerFactory.getLogger(UiRuntimeInstanceProperties.class);

    /**
     * members index 조회 상한입니다.
     *
     * <p>index 는 만료 session 도 담는 정리형이라 replica 수보다 큽니다. 화면 조회 경로이므로
     * 무한 조회를 두지 않습니다.</p>
     */
    private long scanLimit = 200L;

    /**
     * 기동 시 설정 유효성을 검증합니다.
     */
    @PostConstruct
    public void validate() {
        if (scanLimit <= 0L) {
            throw new IllegalStateException(
                    "tc.ui.backend.runtime-instance.scan-limit 는 양수여야 합니다. value=" + scanLimit);
        }
        log.info("UiRuntimeInstanceProperties 검증 완료. scanLimit={}", scanLimit);
    }

    public long getScanLimit() {
        return scanLimit;
    }

    public void setScanLimit(final long scanLimit) {
        this.scanLimit = scanLimit;
    }
}
