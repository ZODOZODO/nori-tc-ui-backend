package com.nori.tc.ui.adapters.redis.async;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 비동기 결과 저장소 관련 설정 바인딩 클래스입니다.
 *
 * <p>바인딩 대상 (config/tc-ui-backend.properties):</p>
 * <pre>
 * tc.ui.backend.async.result-ttl-seconds=600
 * </pre>
 *
 * <p>사용처:</p>
 * <ul>
 *   <li>{@code resultTtlSeconds}: {@code AsyncResultStoreService}에서
 *       {@code tc:ui:backend:async:{traceId}} 키의 Redis TTL 설정에 사용합니다.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "tc.ui.backend.async")
public class UiAsyncProperties {

    private static final Logger log = LoggerFactory.getLogger(UiAsyncProperties.class);

    /**
     * 비동기 결과 저장 TTL(초).
     *
     * <p>eqp_start/end reply 가 저장된 후 front polling 이 가능한 최대 시간입니다.
     * 0이면 만료 없이 보관합니다 (운영 환경에서는 권장하지 않습니다).</p>
     */
    private long resultTtlSeconds = 600L;

    /**
     * 설정값 유효성을 검증합니다.
     *
     * <p>resultTtlSeconds 가 음수면 기동을 중단합니다.
     * 0은 허용(만료 없음)하지만 운영 환경에서는 WARN 로그를 출력합니다.</p>
     */
    @PostConstruct
    public void validate() {
        if (resultTtlSeconds < 0L) {
            throw new IllegalStateException(
                    "tc.ui.backend.async.result-ttl-seconds 는 0 이상이어야 합니다. "
                            + "현재값=" + resultTtlSeconds);
        }
        if (resultTtlSeconds == 0L) {
            log.warn("tc.ui.backend.async.result-ttl-seconds=0 — 비동기 결과가 만료 없이 보관됩니다. "
                    + "운영 환경에서는 적절한 TTL 설정을 권장합니다.");
        }
        log.info("UiAsyncProperties 검증 완료. resultTtlSeconds={}", resultTtlSeconds);
    }

    public long getResultTtlSeconds() {
        return resultTtlSeconds;
    }

    public void setResultTtlSeconds(final long resultTtlSeconds) {
        this.resultTtlSeconds = resultTtlSeconds;
    }
}
