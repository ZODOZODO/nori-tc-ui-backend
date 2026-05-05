package com.nori.tc.ui.starter;

import com.nori.tc.ui.core.service.DeployService;
import com.nori.tc.ui.core.service.DeployTraceRegistry;
import com.nori.tc.ui.core.service.EqpRepairTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UiBackendRuntimeHealthIndicatorTest {

    @Test
    void shouldReturnOutOfServiceWhenRepairBacklogExists() {
        final DeployService deployService = mock(DeployService.class);
        final DeployTraceRegistry deployTraceRegistry = mock(DeployTraceRegistry.class);
        final EqpRepairTaskService repairTaskService = mock(EqpRepairTaskService.class);
        final LettuceConnectionFactory gatewayRedisFactory = mock(LettuceConnectionFactory.class);
        final LettuceConnectionFactory businessRedisFactory = mock(LettuceConnectionFactory.class);
        final RedisConnection gatewayConnection = mock(RedisConnection.class);
        final RedisConnection businessConnection = mock(RedisConnection.class);

        when(gatewayRedisFactory.getConnection()).thenReturn(gatewayConnection);
        when(businessRedisFactory.getConnection()).thenReturn(businessConnection);
        when(gatewayConnection.ping()).thenReturn("PONG");
        when(businessConnection.ping()).thenReturn("PONG");

        when(repairTaskService.backlogCount()).thenReturn(1);
        when(deployTraceRegistry.pendingCount()).thenReturn(0);
        when(deployService.targetExecutorActiveCount()).thenReturn(0);
        when(deployService.targetExecutorQueueSize()).thenReturn(0);
        when(deployService.targetConcurrency()).thenReturn(4);

        final UiBackendRuntimeHealthIndicator indicator = new UiBackendRuntimeHealthIndicator(
                deployService,
                deployTraceRegistry,
                repairTaskService,
                gatewayRedisFactory,
                businessRedisFactory
        );

        // test
        assertEquals(Status.OUT_OF_SERVICE, indicator.health().getStatus());
    }
}
