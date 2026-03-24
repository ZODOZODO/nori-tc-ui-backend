package com.nori.tc.ui.core.auth;

import com.nori.tc.ui.core.properties.UiAuthProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 캐시 히트 토큰의 재검증 시각을 관리하는 bounded TTL 캐시입니다.
 *
 * <p>기존 무제한 ConcurrentHashMap 누수 문제를 막기 위해 access-order + TTL 기반으로 관리합니다.</p>
 */
@Component
public class UiTokenRecheckWindowCache {

    private final long ttlMillis;
    private final int maxEntries;
    private final Map<String, Long> nextCheckEpochMsByToken;

    public UiTokenRecheckWindowCache(final UiAuthProperties authProperties) {
        this(authProperties.tokenRecheckIntervalSeconds(), authProperties.tokenRecheckMaxEntries(), null);
    }

    @Autowired
    public UiTokenRecheckWindowCache(
            final UiAuthProperties authProperties,
            @Nullable final MeterRegistry meterRegistry
    ) {
        this(authProperties.tokenRecheckIntervalSeconds(), authProperties.tokenRecheckMaxEntries(), meterRegistry);
    }

    private UiTokenRecheckWindowCache(
            final int ttlSeconds,
            final int maxEntries,
            @Nullable final MeterRegistry meterRegistry
    ) {
        this.ttlMillis = Math.max(1L, ttlSeconds) * 1000L;
        this.maxEntries = Math.max(1, maxEntries);
        this.nextCheckEpochMsByToken = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<String, Long> eldest) {
                return size() > UiTokenRecheckWindowCache.this.maxEntries;
            }
        };
        if (meterRegistry != null) {
            Gauge.builder("ui.auth.token.recheck.cache.size", this, UiTokenRecheckWindowCache::size)
                    .description("토큰 재검증 윈도우 캐시 크기")
                    .register(meterRegistry);
        }
    }

    public static UiTokenRecheckWindowCache createStandalone(final int ttlSeconds, final int maxEntries) {
        return new UiTokenRecheckWindowCache(ttlSeconds, maxEntries, null);
    }

    public synchronized boolean shouldRecheck(final String token, final long nowEpochMs) {
        purgeExpiredEntries(nowEpochMs);
        final Long nextCheckAt = nextCheckEpochMsByToken.get(token);
        return nextCheckAt == null || nowEpochMs >= nextCheckAt;
    }

    public synchronized void markValidated(final String token, final long nowEpochMs) {
        purgeExpiredEntries(nowEpochMs);
        nextCheckEpochMsByToken.put(token, nowEpochMs + ttlMillis);
    }

    public synchronized void evict(final String token) {
        nextCheckEpochMsByToken.remove(token);
    }

    public synchronized int size() {
        purgeExpiredEntries(System.currentTimeMillis());
        return nextCheckEpochMsByToken.size();
    }

    private void purgeExpiredEntries(final long nowEpochMs) {
        final Iterator<Map.Entry<String, Long>> iterator = nextCheckEpochMsByToken.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<String, Long> entry = iterator.next();
            if (entry.getValue() <= nowEpochMs) {
                iterator.remove();
            }
        }
    }
}
