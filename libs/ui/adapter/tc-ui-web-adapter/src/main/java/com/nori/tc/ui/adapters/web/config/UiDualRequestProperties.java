package com.nori.tc.ui.adapters.web.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * EqpController DualResponse 대기 타임아웃 설정 바인딩 클래스입니다.
 *
 * <p>바인딩 대상 (config/tc-ui-backend.properties):</p>
 * <pre>
 * tc.ui.backend.async.dual-request-timeout-ms=10000
 * </pre>
 *
 * <p>사용처:</p>
 * <ul>
 *   <li>{@code EqpController}: eqp_create / eqp_update / eqp_delete 요청 시
 *       {@code DualResponseRegistry.register(traceId, dualRequestTimeoutMs)} 호출에 사용합니다.</li>
 * </ul>
 *
 * <p>타임아웃 동작:</p>
 * <p>설정값이 만료되면 {@code DualResponseRegistry}의 {@code CompletableFuture}가
 * {@code TimeoutException}으로 완료되고, HTTP 504 Gateway Timeout이 반환됩니다.</p>
 */
@ConfigurationProperties(prefix = "tc.ui.backend.async")
public class UiDualRequestProperties {

    private static final Logger log = LoggerFactory.getLogger(UiDualRequestProperties.class);

    /**
     * eqp_create / eqp_update / eqp_delete DualResponse 대기 최대 시간 (밀리초).
     *
     * <p>gateway + business 양쪽 응답을 기다리는 최대 시간입니다.
     * eqp_update의 경우 gateway 내부에서 설비 재시작이 필요할 수 있어
     * 응답이 늦을 수 있으므로 충분한 값으로 설정하십시오 (기본 10초).</p>
     */
    private long dualRequestTimeoutMs = 10_000L;

    /**
     * 기동 시 설정값 유효성을 검증합니다.
     *
     * <p>dualRequestTimeoutMs가 0 이하이면 기동을 중단합니다.</p>
     */
    @PostConstruct
    public void validate() {
        if (dualRequestTimeoutMs <= 0L) {
            throw new IllegalStateException(
                    "tc.ui.backend.async.dual-request-timeout-ms 는 양수여야 합니다. "
                            + "현재값=" + dualRequestTimeoutMs);
        }
        log.info("UiDualRequestProperties 검증 완료. dualRequestTimeoutMs={}ms", dualRequestTimeoutMs);
    }

    public long getDualRequestTimeoutMs() {
        return dualRequestTimeoutMs;
    }

    public void setDualRequestTimeoutMs(final long dualRequestTimeoutMs) {
        this.dualRequestTimeoutMs = dualRequestTimeoutMs;
    }
}
