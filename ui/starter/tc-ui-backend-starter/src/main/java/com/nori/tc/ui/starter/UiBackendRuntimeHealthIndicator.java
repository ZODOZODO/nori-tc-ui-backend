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

import java.util.Objects;

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
        final String gatewayRedisPing = ping(gatewayRedisFactory);
        final String businessRedisPing = ping(businessRedisFactory);
        final int repairBacklogCount = repairTaskService.backlogCount();
        final int pendingDeployTraceCount = deployTraceRegistry.pendingCount();
        final int targetExecutorActiveCount = deployService.targetExecutorActiveCount();
        final int targetExecutorQueueSize = deployService.targetExecutorQueueSize();
        final boolean saturated = targetExecutorQueueSize > 0
                && targetExecutorActiveCount >= deployService.targetConcurrency();
        final boolean redisReady = isRedisReady(gatewayRedisPing) && isRedisReady(businessRedisPing);

        final Status status;
        if (!redisReady) {
            status = Status.DOWN;
        } else if (repairBacklogCount > 0 || saturated) {
            status = Status.OUT_OF_SERVICE;
        } else {
            status = Status.UP;
        }

        return Health.status(status)
                .withDetail("gatewayRedisPing", gatewayRedisPing)
                .withDetail("businessRedisPing", businessRedisPing)
                .withDetail("repairBacklogCount", repairBacklogCount)
                .withDetail("pendingDeployTraceCount", pendingDeployTraceCount)
                .withDetail("targetExecutorActiveCount", targetExecutorActiveCount)
                .withDetail("targetExecutorQueueSize", targetExecutorQueueSize)
                .withDetail("targetConcurrency", deployService.targetConcurrency())
                .build();
    }

    private static String ping(final LettuceConnectionFactory connectionFactory) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            return connection.ping();
        } catch (Exception ex) {
            return "ERROR:" + ex.getClass().getSimpleName();
        }
    }

    private static boolean isRedisReady(final String pingResult) {
        return pingResult != null && "PONG".equalsIgnoreCase(pingResult);
    }
}
