package com.nori.tc.ui.adapters.web.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 설비 lifecycle 비동기 요청의 결과 대기 타임아웃 설정 바인딩 클래스입니다.
 *
 * <p>바인딩 대상 (config/tc-ui-backend.properties):</p>
 * <pre>
 * tc.ui.backend.async.dual-request-timeout-ms=10000
 * </pre>
 *
 * <p>사용처 — {@code EqpController} 의 lifecycle 계열 5 종이며, 전부
 * {@code AsyncResultStorePort} 경로다:</p>
 * <ul>
 *   <li>{@code start} / {@code end} / {@code reconnect}</li>
 *   <li>{@code polling-group start} / {@code polling-group end}</li>
 * </ul>
 *
 * <p>이름의 "dual" 은 Gateway + Business 양쪽 응답을 기다리던 시절의 잔재이며 현재 의미와 맞지
 * 않는다. 설정 key 는 배포된 config 파일과의 호환 때문에 그대로 둔다 — 바꾸려면 전 환경의
 * properties 를 함께 옮겨야 한다. eqp_create / update / delete 는 이 값을 쓰지 않고 조정 원장의
 * operation 으로 진행 상태를 조회한다(§13.2).</p>
 *
 * <p>타임아웃 동작: front 가 {@code GET /api/async/{traceId}} 폴링으로 결과를 확인하며, 이 값은
 * 결과 보관 시한으로 전달된다.</p>
 */
@ConfigurationProperties(prefix = "tc.ui.backend.async")
public class UiDualRequestProperties {

    private static final Logger log = LoggerFactory.getLogger(UiDualRequestProperties.class);

    /**
     * 설비 lifecycle(start / end / reconnect / polling-group) 비동기 결과 대기 최대 시간 (밀리초).
     *
     * <p>gateway 내부에서 설비 재연결이 필요할 수 있어 응답이 늦을 수 있으므로 충분한 값으로
     * 설정하십시오 (기본 10초).</p>
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
