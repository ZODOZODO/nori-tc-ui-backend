package com.nori.tc.ui.core.auth;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nullable;
import org.springframework.stereotype.Component;

/**
 * 인증/세션 경로 메트릭 기록기입니다.
 *
 * <p>Actuator/Micrometer가 없는 테스트 환경에서는 no-op로 동작합니다.</p>
 */
@Component
public class UiAuthMetrics {

    private final MeterRegistry meterRegistry;

    public UiAuthMetrics(@Nullable final MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public static UiAuthMetrics noop() {
        return new UiAuthMetrics(null);
    }

    public void recordLoginSuccess() {
        increment("ui.auth.login.success");
    }

    public void recordLoginFailure(final String reason) {
        increment("ui.auth.login.failure", "reason", reason);
    }

    public void recordLogoutSuccess() {
        increment("ui.auth.logout.success");
    }

    public void recordLogoutCacheEvictFailure() {
        increment("ui.auth.logout.cache.evict.failure");
    }

    public void recordTokenCacheHit() {
        increment("ui.auth.token.cache.hit");
    }

    public void recordTokenCacheMiss() {
        increment("ui.auth.token.cache.miss");
    }

    public void recordTokenValidationFailure(final String reason) {
        increment("ui.auth.token.validation.failure", "reason", reason);
    }

    public void recordTokenRecheckInvalidated() {
        increment("ui.auth.token.recheck.invalidated");
    }

    public void recordLastSeenUpdateFailure() {
        increment("ui.auth.last-seen.update.failure");
    }

    private void increment(final String meterName) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(meterName).increment();
    }

    private void increment(final String meterName, final String tagKey, final String tagValue) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(meterName, tagKey, sanitizeTagValue(tagValue)).increment();
    }

    private static String sanitizeTagValue(final String tagValue) {
        if (tagValue == null || tagValue.isBlank()) {
            return "unknown";
        }
        return tagValue.trim().toLowerCase();
    }
}
