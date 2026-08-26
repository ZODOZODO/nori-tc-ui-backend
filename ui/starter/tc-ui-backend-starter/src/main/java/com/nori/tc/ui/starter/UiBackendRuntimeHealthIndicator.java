package com.nori.tc.ui.starter;

import com.nori.tc.ui.core.coordination.UiCoordinationStatus;
import com.nori.tc.ui.core.service.DeployService;
import com.nori.tc.ui.core.service.DeployTraceRegistry;
import com.nori.tc.ui.core.service.EqpRepairTaskService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * UI backend deploy/repair/dual-redis readiness surface를 제공합니다.
 */
public class UiBackendRuntimeHealthIndicator implements HealthIndicator {

    /**
     * repair 잔여 건수를 조회하지 못했음을 나타내는 값입니다.
     *
     * <p>{@code Health.Builder} 가 null detail 을 거부하므로 키를 지우는 대신 센티넬을 씁니다 — 키가
     * 사라지면 이 값을 읽던 대시보드가 조용히 비어 버립니다. 실패 사유는 {@code repairBacklogError}
     * 로 함께 실립니다.</p>
     */
    private static final int BACKLOG_UNKNOWN = -1;

    private final DeployService deployService;
    private final DeployTraceRegistry deployTraceRegistry;
    private final EqpRepairTaskService repairTaskService;
    private final LettuceConnectionFactory gatewayRedisFactory;
    private final LettuceConnectionFactory businessRedisFactory;
    private final UiCoordinationStatus coordinationStatus;

    public UiBackendRuntimeHealthIndicator(
            final DeployService deployService,
            final DeployTraceRegistry deployTraceRegistry,
            final EqpRepairTaskService repairTaskService,
            final UiCoordinationStatus coordinationStatus,
            @Qualifier("gatewayLettuceConnectionFactory") final LettuceConnectionFactory gatewayRedisFactory,
            @Qualifier("businessLettuceConnectionFactory") final LettuceConnectionFactory businessRedisFactory
    ) {
        this.deployService = Objects.requireNonNull(deployService, "deployService is null");
        this.deployTraceRegistry = Objects.requireNonNull(deployTraceRegistry, "deployTraceRegistry is null");
        this.repairTaskService = Objects.requireNonNull(repairTaskService, "repairTaskService is null");
        this.coordinationStatus = Objects.requireNonNull(coordinationStatus, "coordinationStatus is null");
        this.gatewayRedisFactory = Objects.requireNonNull(gatewayRedisFactory, "gatewayRedisFactory is null");
        this.businessRedisFactory = Objects.requireNonNull(businessRedisFactory, "businessRedisFactory is null");
    }

    @Override
    public Health health() {
        final RedisRuntimeStats gatewayRedisStats = collectRedisStats(gatewayRedisFactory);
        final RedisRuntimeStats businessRedisStats = collectRedisStats(businessRedisFactory);
        final RepairBacklogProbe repairBacklog = probeRepairBacklog();
        final int pendingDeployTraceCount = deployTraceRegistry.pendingCount();
        final int targetExecutorActiveCount = deployService.targetExecutorActiveCount();
        final int targetExecutorQueueSize = deployService.targetExecutorQueueSize();
        final boolean saturated = targetExecutorQueueSize > 0
                && targetExecutorActiveCount >= deployService.targetConcurrency();
        final boolean redisReady = gatewayRedisStats.ready() && businessRedisStats.ready();
        final boolean coordinationReady = coordinationStatus.readyForMutation();

        /*
         * §13.11 — readiness 는 Redis ping 만이 아니라 조정 상태를 함께 본다. atomic script 검증 실패,
         * cluster epoch mismatch, catalog revision mismatch, recovery backlog 초과, self-fence 는
         * 모두 "mutation 을 받으면 안 되는" 상태다. repair 잔존/executor 포화는 처리 중 상태이므로
         * 종전대로 DOWN 사유가 아니다.
         */
        final Status status = redisReady && coordinationReady ? Status.UP : Status.DOWN;

        final Health.Builder builder = Health.status(status)
                .withDetail("coordination", coordinationStatus.details())
                .withDetail("coordinationReady", coordinationReady)
                .withDetail("gatewayRedisPing", gatewayRedisStats.ping())
                .withDetail("businessRedisPing", businessRedisStats.ping())
                .withDetail("gatewayRedisStats", gatewayRedisStats.details())
                .withDetail("businessRedisStats", businessRedisStats.details())
                .withDetail("repairBacklogCount", repairBacklog.count());
        if (repairBacklog.error() != null) {
            builder.withDetail("repairBacklogError", repairBacklog.error());
        }
        return builder
                .withDetail("pendingDeployTraceCount", pendingDeployTraceCount)
                .withDetail("targetExecutorActiveCount", targetExecutorActiveCount)
                .withDetail("targetExecutorQueueSize", targetExecutorQueueSize)
                .withDetail("targetConcurrency", deployService.targetConcurrency())
                .build();
    }

    /**
     * repair 잔여 건수를 조회합니다 — 실패해도 <b>던지지 않습니다</b>.
     *
     * <p>이 조회는 Redis 를 탑니다. Redis 가 내려가면 여기서 예외가 나가는데, health indicator 밖으로
     * 나가면 actuator 가 <b>500</b> 을 응답합니다. §13.11 은 그런 상황에서 readiness 를 {@code false}
     * 로 요구하므로 DOWN + 사유가 실린 503 이어야 합니다 — 500 은 운영자에게 "왜" 를 알려주지
     * 못합니다. 상태 판정은 {@code redisReady} 가 이미 담당하므로 여기서는 사유만 남깁니다.</p>
     */
    private RepairBacklogProbe probeRepairBacklog() {
        try {
            return new RepairBacklogProbe(repairTaskService.backlogCount(), null);
        } catch (Exception ex) {
            return new RepairBacklogProbe(BACKLOG_UNKNOWN, ex.getClass().getSimpleName());
        }
    }

    private static RedisRuntimeStats collectRedisStats(final LettuceConnectionFactory connectionFactory) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            final String ping = connection.ping();
            Long keyCount = null;
            Properties info = null;
            String statsError = null;

            try {
                keyCount = connection.serverCommands().dbSize();
            } catch (Exception ex) {
                statsError = appendStatsError(statsError, "dbSize", ex);
            }

            try {
                info = connection.serverCommands().info();
            } catch (Exception ex) {
                statsError = appendStatsError(statsError, "info", ex);
            }

            return new RedisRuntimeStats(
                    ping,
                    keyCount,
                    parseLong(info, "used_memory"),
                    info == null ? null : info.getProperty("used_memory_human"),
                    parseLong(info, "evicted_keys"),
                    statsError
            );
        } catch (Exception ex) {
            return RedisRuntimeStats.error("ERROR:" + ex.getClass().getSimpleName());
        }
    }

    private static boolean isRedisReady(final String pingResult) {
        return pingResult != null && "PONG".equalsIgnoreCase(pingResult);
    }

    private static Long parseLong(final Properties info, final String key) {
        if (info == null) {
            return null;
        }
        final String value = info.getProperty(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String appendStatsError(
            final String current,
            final String target,
            final Exception ex
    ) {
        final String next = target + ":" + ex.getClass().getSimpleName();
        if (current == null || current.isBlank()) {
            return next;
        }
        return current + "," + next;
    }

    /**
     * repair 잔여 건수 조회 결과입니다.
     *
     * @param count 잔여 건수(조회 실패면 {@link #BACKLOG_UNKNOWN})
     * @param error 조회 실패 사유(성공이면 null)
     */
    private record RepairBacklogProbe(int count, String error) {
    }

    private record RedisRuntimeStats(
            String ping,
            Long keyCount,
            Long usedMemoryBytes,
            String usedMemoryHuman,
            Long evictedKeys,
            String statsError
    ) {

        private static RedisRuntimeStats error(final String ping) {
            return new RedisRuntimeStats(
                    ping,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }

        private boolean ready() {
            return isRedisReady(ping);
        }

        private Map<String, Object> details() {
            final Map<String, Object> details = new LinkedHashMap<>();
            details.put("keyCount", keyCount);
            details.put("usedMemoryBytes", usedMemoryBytes);
            details.put("usedMemoryHuman", usedMemoryHuman);
            details.put("evictedKeys", evictedKeys);
            if (statsError != null && !statsError.isBlank()) {
                details.put("statsError", statsError);
            }
            return Collections.unmodifiableMap(details);
        }
    }
}
