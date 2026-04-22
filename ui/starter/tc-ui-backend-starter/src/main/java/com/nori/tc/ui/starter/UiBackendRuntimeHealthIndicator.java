package com.nori.tc.ui.starter;

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

    private final DeployService deployService;
    private final DeployTraceRegistry deployTraceRegistry;
    private final EqpRepairTaskService repairTaskService;
    private final LettuceConnectionFactory gatewayRedisFactory;
    private final LettuceConnectionFactory businessRedisFactory;

    public UiBackendRuntimeHealthIndicator(
            final DeployService deployService,
            final DeployTraceRegistry deployTraceRegistry,
            final EqpRepairTaskService repairTaskService,
            @Qualifier("gatewayLettuceConnectionFactory") final LettuceConnectionFactory gatewayRedisFactory,
            @Qualifier("businessLettuceConnectionFactory") final LettuceConnectionFactory businessRedisFactory
    ) {
        this.deployService = Objects.requireNonNull(deployService, "deployService is null");
        this.deployTraceRegistry = Objects.requireNonNull(deployTraceRegistry, "deployTraceRegistry is null");
        this.repairTaskService = Objects.requireNonNull(repairTaskService, "repairTaskService is null");
        this.gatewayRedisFactory = Objects.requireNonNull(gatewayRedisFactory, "gatewayRedisFactory is null");
        this.businessRedisFactory = Objects.requireNonNull(businessRedisFactory, "businessRedisFactory is null");
    }

    @Override
    public Health health() {
        final RedisRuntimeStats gatewayRedisStats = collectRedisStats(gatewayRedisFactory);
        final RedisRuntimeStats businessRedisStats = collectRedisStats(businessRedisFactory);
        final int repairBacklogCount = repairTaskService.backlogCount();
        final int pendingDeployTraceCount = deployTraceRegistry.pendingCount();
        final int targetExecutorActiveCount = deployService.targetExecutorActiveCount();
        final int targetExecutorQueueSize = deployService.targetExecutorQueueSize();
        final boolean saturated = targetExecutorQueueSize > 0
                && targetExecutorActiveCount >= deployService.targetConcurrency();
        final boolean redisReady = gatewayRedisStats.ready() && businessRedisStats.ready();

        final Status status;
        if (!redisReady) {
            status = Status.DOWN;
        } else if (repairBacklogCount > 0 || saturated) {
            status = Status.OUT_OF_SERVICE;
        } else {
            status = Status.UP;
        }

        return Health.status(status)
                .withDetail("gatewayRedisPing", gatewayRedisStats.ping())
                .withDetail("businessRedisPing", businessRedisStats.ping())
                .withDetail("gatewayRedisStats", gatewayRedisStats.details())
                .withDetail("businessRedisStats", businessRedisStats.details())
                .withDetail("repairBacklogCount", repairBacklogCount)
                .withDetail("pendingDeployTraceCount", pendingDeployTraceCount)
                .withDetail("targetExecutorActiveCount", targetExecutorActiveCount)
                .withDetail("targetExecutorQueueSize", targetExecutorQueueSize)
                .withDetail("targetConcurrency", deployService.targetConcurrency())
                .build();
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
