package com.nori.tc.ui.core.coordination;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * UI Backend 클러스터 조정 런타임 설정입니다(§9.3 / §13).
 *
 * <p>기본값은 §9.3 권장 초기값이며 운영 값은 배포 설정(exec)이 소유합니다 — 라이브러리에 상수로
 * 박지 않고 전부 property 로 노출합니다(Plan §8 B4/B5).</p>
 *
 * <p>{@code enabled} 기본값은 <b>true</b> 입니다(Plan §8 B16). 기본값을 false 로 두면 배포 설정이
 * 값을 명시하지 않는 한 §13 전체가 조용히 비활성화됩니다. cluster epoch 미발급 시 기동 실패는
 * coordination context 의 몫이며 그 fail-closed 자체가 §23.9 가 요구하는 동작입니다.</p>
 */
@ConfigurationProperties(prefix = "tc.ui.backend.coordination")
public class UiCoordinationProperties {

    private static final Logger log = LoggerFactory.getLogger(UiCoordinationProperties.class);

    private boolean enabled = true;
    private String serviceName = "tc-ui-backend";
    private String protocolVersion = "2";
    private boolean allowLocalSessionIdentity;

    private long heartbeatIntervalMs = 5_000L;
    private long heartbeatTtlMs = 15_000L;
    private long redisCommandTimeoutMs = 1_000L;
    private long selfFenceMs = 2_000L;
    private long clockSkewMarginMs = 1_000L;

    private long executorLeaseTtlMs = 15_000L;
    private long reconcileIntervalMs = 1_000L;
    private int pendingScanLimit = 500;
    private int participantScanLimit = 1_000;
    private int recoveryBacklogLimit = 200;

    /*
     * 관찰 deadline. 이 값을 넘겨도 operation 은 terminal 이 되지 않고 RECONCILING 으로만 표시된다
     * (§7.2/§13.3) — 여기서 terminal 을 만들면 늦게 도착한 적용이 결과를 뒤집을 수 없게 된다.
     */
    private long observationDeadlineMs = 120_000L;

    /** Business participant 를 요구하는 서비스명입니다. */
    private String businessServiceName = "tc-business";

    /** Gateway participant 를 요구하는 서비스명입니다. */
    private String gatewayServiceName = "tc-gateway";

    /**
     * §9.3 의 lease 안전 관계식을 기동 시점에 강제합니다.
     */
    @PostConstruct
    public void validate() {
        if (!enabled) {
            log.info("UI_COORDINATION_DISABLED. tc.ui.backend.coordination.enabled=false — 조정 원장 경로를 사용하지 않습니다.");
            return;
        }
        requirePositive("heartbeatIntervalMs", heartbeatIntervalMs);
        requirePositive("heartbeatTtlMs", heartbeatTtlMs);
        requirePositive("redisCommandTimeoutMs", redisCommandTimeoutMs);
        requirePositive("selfFenceMs", selfFenceMs);
        requirePositive("executorLeaseTtlMs", executorLeaseTtlMs);
        requirePositive("reconcileIntervalMs", reconcileIntervalMs);
        requirePositive("observationDeadlineMs", observationDeadlineMs);
        requirePositive("pendingScanLimit", pendingScanLimit);
        requirePositive("participantScanLimit", participantScanLimit);
        requirePositive("recoveryBacklogLimit", recoveryBacklogLimit);

        assertLeaseSafety("heartbeat", heartbeatTtlMs, redisCommandTimeoutMs);
        assertLeaseSafety("operation-executor", executorLeaseTtlMs, redisCommandTimeoutMs);

        log.info("UI_COORDINATION_ENABLED. serviceName={}, protocolVersion={}, heartbeatTtlMs={}, "
                        + "executorLeaseTtlMs={}, reconcileIntervalMs={}, observationDeadlineMs={}",
                serviceName, protocolVersion, heartbeatTtlMs, executorLeaseTtlMs,
                reconcileIntervalMs, observationDeadlineMs);
    }

    /**
     * 실제 Redis client 의 command timeout 으로 §9.3 관계식을 다시 강제합니다(Plan §8 B26).
     *
     * <p>{@code redisCommandTimeoutMs} 는 자기 선언 값이라 실제 Lettuce 설정과 어긋날 수 있습니다.
     * 선언값이 실제보다 작으면 관계식이 통과해도 실제 worst case 가 lease 예산을 넘겨 stale executor 가
     * 만료 뒤에도 전이를 시도합니다.</p>
     *
     * @param actualRedisCommandTimeoutMs 실제 Redis client command timeout(ms)
     */
    public void assertLeaseSafetyWithActualRedisTimeout(final long actualRedisCommandTimeoutMs) {
        if (!enabled) {
            return;
        }
        requirePositive("actualRedisCommandTimeoutMs", actualRedisCommandTimeoutMs);
        if (actualRedisCommandTimeoutMs != redisCommandTimeoutMs) {
            log.warn("UI_COORDINATION_REDIS_TIMEOUT_MISMATCH. 선언값과 실제 client 설정이 다릅니다 — "
                            + "안전 관계식은 실제값으로 강제합니다. declaredMs={}, actualMs={}",
                    redisCommandTimeoutMs, actualRedisCommandTimeoutMs);
        }
        assertLeaseSafety("heartbeat", heartbeatTtlMs, actualRedisCommandTimeoutMs);
        assertLeaseSafety("operation-executor", executorLeaseTtlMs, actualRedisCommandTimeoutMs);
    }

    private void assertLeaseSafety(
            final String leaseName,
            final long leaseTtlMs,
            final long effectiveRedisCommandTimeoutMs
    ) {
        final long worstCase = effectiveRedisCommandTimeoutMs + selfFenceMs + clockSkewMarginMs;
        final long budget = leaseTtlMs - heartbeatIntervalMs;
        if (worstCase >= budget) {
            throw new IllegalStateException(
                    "coordination lease safety relation violated for " + leaseName + " lease: "
                            + "redisCommandTimeoutMs(" + effectiveRedisCommandTimeoutMs + ") + selfFenceMs("
                            + selfFenceMs + ") + clockSkewMarginMs(" + clockSkewMarginMs + ") = " + worstCase
                            + " must be < leaseTtlMs(" + leaseTtlMs + ") - heartbeatIntervalMs("
                            + heartbeatIntervalMs + ") = " + budget);
        }
    }

    private static void requirePositive(final String name, final long value) {
        if (value <= 0L) {
            throw new IllegalStateException("tc.ui.backend.coordination." + name + " must be > 0 but was " + value);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(final String serviceName) {
        this.serviceName = serviceName;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(final String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public boolean isAllowLocalSessionIdentity() {
        return allowLocalSessionIdentity;
    }

    public void setAllowLocalSessionIdentity(final boolean allowLocalSessionIdentity) {
        this.allowLocalSessionIdentity = allowLocalSessionIdentity;
    }

    public long getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public void setHeartbeatIntervalMs(final long heartbeatIntervalMs) {
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    public long getHeartbeatTtlMs() {
        return heartbeatTtlMs;
    }

    public void setHeartbeatTtlMs(final long heartbeatTtlMs) {
        this.heartbeatTtlMs = heartbeatTtlMs;
    }

    public long getRedisCommandTimeoutMs() {
        return redisCommandTimeoutMs;
    }

    public void setRedisCommandTimeoutMs(final long redisCommandTimeoutMs) {
        this.redisCommandTimeoutMs = redisCommandTimeoutMs;
    }

    public long getSelfFenceMs() {
        return selfFenceMs;
    }

    public void setSelfFenceMs(final long selfFenceMs) {
        this.selfFenceMs = selfFenceMs;
    }

    public long getClockSkewMarginMs() {
        return clockSkewMarginMs;
    }

    public void setClockSkewMarginMs(final long clockSkewMarginMs) {
        this.clockSkewMarginMs = clockSkewMarginMs;
    }

    public long getExecutorLeaseTtlMs() {
        return executorLeaseTtlMs;
    }

    public void setExecutorLeaseTtlMs(final long executorLeaseTtlMs) {
        this.executorLeaseTtlMs = executorLeaseTtlMs;
    }

    public long getReconcileIntervalMs() {
        return reconcileIntervalMs;
    }

    public void setReconcileIntervalMs(final long reconcileIntervalMs) {
        this.reconcileIntervalMs = reconcileIntervalMs;
    }

    public int getPendingScanLimit() {
        return pendingScanLimit;
    }

    public void setPendingScanLimit(final int pendingScanLimit) {
        this.pendingScanLimit = pendingScanLimit;
    }

    public int getParticipantScanLimit() {
        return participantScanLimit;
    }

    public void setParticipantScanLimit(final int participantScanLimit) {
        this.participantScanLimit = participantScanLimit;
    }

    public int getRecoveryBacklogLimit() {
        return recoveryBacklogLimit;
    }

    public void setRecoveryBacklogLimit(final int recoveryBacklogLimit) {
        this.recoveryBacklogLimit = recoveryBacklogLimit;
    }

    public long getObservationDeadlineMs() {
        return observationDeadlineMs;
    }

    public void setObservationDeadlineMs(final long observationDeadlineMs) {
        this.observationDeadlineMs = observationDeadlineMs;
    }

    public String getBusinessServiceName() {
        return businessServiceName;
    }

    public void setBusinessServiceName(final String businessServiceName) {
        this.businessServiceName = businessServiceName;
    }

    public String getGatewayServiceName() {
        return gatewayServiceName;
    }

    public void setGatewayServiceName(final String gatewayServiceName) {
        this.gatewayServiceName = gatewayServiceName;
    }
}
