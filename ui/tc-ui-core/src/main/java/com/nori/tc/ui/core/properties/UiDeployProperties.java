package com.nori.tc.ui.core.properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deploy orchestration 관련 설정 바인딩입니다.
 *
 * <p>T3 시점에는 scenario catalog 로딩과 planner 검증에 필요한 값부터 사용하고,
 * 이후 T4~T7에서 실행 엔진/timeout/retention 설정까지 같은 객체를 재사용합니다.</p>
 */
@ConfigurationProperties(prefix = "tc.ui.backend.deploy")
public record UiDeployProperties(
        String scenarioDirectory,
        int maxTargetsPerRequest,
        int targetConcurrency,
        int gatewayDispatchConcurrency,
        int businessDispatchConcurrency,
        long timeoutScanIntervalMs,
        long targetLockExtraTtlMs,
        long summaryCacheTtlSeconds,
        long liveCacheTtlSeconds,
        long traceCacheTtlSeconds,
        long replyDedupeTtlSeconds,
        int snapshotRetentionDays,
        int failedSnapshotRetentionDays
) {

    private static final Logger log = LoggerFactory.getLogger(UiDeployProperties.class);

    private static final String DEFAULT_SCENARIO_DIRECTORY = "./runtime/ui-backend/deploy-scenarios";
    private static final int DEFAULT_MAX_TARGETS_PER_REQUEST = 100;
    private static final int DEFAULT_TARGET_CONCURRENCY = 10;
    private static final int DEFAULT_GATEWAY_DISPATCH_CONCURRENCY = 20;
    private static final int DEFAULT_BUSINESS_DISPATCH_CONCURRENCY = 20;
    private static final long DEFAULT_TIMEOUT_SCAN_INTERVAL_MS = 1000L;
    private static final long DEFAULT_TARGET_LOCK_EXTRA_TTL_MS = 30000L;
    private static final long DEFAULT_SUMMARY_CACHE_TTL_SECONDS = 86400L;
    private static final long DEFAULT_LIVE_CACHE_TTL_SECONDS = 86400L;
    private static final long DEFAULT_TRACE_CACHE_TTL_SECONDS = 3900L;
    private static final long DEFAULT_REPLY_DEDUPE_TTL_SECONDS = 4200L;
    private static final int DEFAULT_SNAPSHOT_RETENTION_DAYS = 30;
    private static final int DEFAULT_FAILED_SNAPSHOT_RETENTION_DAYS = 90;

    public UiDeployProperties {
        scenarioDirectory = normalizeScenarioDirectory(scenarioDirectory);
        maxTargetsPerRequest = normalizePositiveInt(
                "max-targets-per-request",
                maxTargetsPerRequest,
                DEFAULT_MAX_TARGETS_PER_REQUEST
        );
        targetConcurrency = normalizePositiveInt(
                "target-concurrency",
                targetConcurrency,
                DEFAULT_TARGET_CONCURRENCY
        );
        gatewayDispatchConcurrency = normalizePositiveInt(
                "gateway-dispatch-concurrency",
                gatewayDispatchConcurrency,
                DEFAULT_GATEWAY_DISPATCH_CONCURRENCY
        );
        businessDispatchConcurrency = normalizePositiveInt(
                "business-dispatch-concurrency",
                businessDispatchConcurrency,
                DEFAULT_BUSINESS_DISPATCH_CONCURRENCY
        );
        timeoutScanIntervalMs = normalizePositiveLong(
                "timeout-scan-interval-ms",
                timeoutScanIntervalMs,
                DEFAULT_TIMEOUT_SCAN_INTERVAL_MS
        );
        targetLockExtraTtlMs = normalizePositiveLong(
                "target-lock-extra-ttl-ms",
                targetLockExtraTtlMs,
                DEFAULT_TARGET_LOCK_EXTRA_TTL_MS
        );
        summaryCacheTtlSeconds = normalizePositiveLong(
                "summary-cache-ttl-seconds",
                summaryCacheTtlSeconds,
                DEFAULT_SUMMARY_CACHE_TTL_SECONDS
        );
        liveCacheTtlSeconds = normalizePositiveLong(
                "live-cache-ttl-seconds",
                liveCacheTtlSeconds,
                DEFAULT_LIVE_CACHE_TTL_SECONDS
        );
        traceCacheTtlSeconds = normalizePositiveLong(
                "trace-cache-ttl-seconds",
                traceCacheTtlSeconds,
                DEFAULT_TRACE_CACHE_TTL_SECONDS
        );
        replyDedupeTtlSeconds = normalizePositiveLong(
                "reply-dedupe-ttl-seconds",
                replyDedupeTtlSeconds,
                DEFAULT_REPLY_DEDUPE_TTL_SECONDS
        );
        snapshotRetentionDays = normalizePositiveInt(
                "snapshot-retention-days",
                snapshotRetentionDays,
                DEFAULT_SNAPSHOT_RETENTION_DAYS
        );
        failedSnapshotRetentionDays = normalizePositiveInt(
                "failed-snapshot-retention-days",
                failedSnapshotRetentionDays,
                DEFAULT_FAILED_SNAPSHOT_RETENTION_DAYS
        );
    }

    private static String normalizeScenarioDirectory(final String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            log.warn("deploy 설정 보정 - scenario-directory 값이 비어 있어 기본값을 적용합니다. 적용값={}",
                    DEFAULT_SCENARIO_DIRECTORY);
            return DEFAULT_SCENARIO_DIRECTORY;
        }
        return rawValue.trim();
    }

    private static int normalizePositiveInt(
            final String propertyName,
            final int rawValue,
            final int defaultValue
    ) {
        if (rawValue > 0) {
            return rawValue;
        }
        log.warn("deploy 설정 보정 - {} 값이 0 이하라 기본값을 적용합니다. 입력값={}, 적용값={}",
                propertyName, rawValue, defaultValue);
        return defaultValue;
    }

    private static long normalizePositiveLong(
            final String propertyName,
            final long rawValue,
            final long defaultValue
    ) {
        if (rawValue > 0) {
            return rawValue;
        }
        log.warn("deploy 설정 보정 - {} 값이 0 이하라 기본값을 적용합니다. 입력값={}, 적용값={}",
                propertyName, rawValue, defaultValue);
        return defaultValue;
    }
}
