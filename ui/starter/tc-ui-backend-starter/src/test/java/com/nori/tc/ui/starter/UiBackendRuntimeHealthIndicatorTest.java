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
    void shouldReturnUpWhenRepairBacklogExistsButRedisReady() {
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
                com.nori.tc.ui.core.coordination.UiCoordinationStatus.disabled(),
                gatewayRedisFactory,
                businessRedisFactory
        );

        // repair 잔존은 서비스 불가 사유가 아님 — Redis 연결만 정상이면 UP
        assertEquals(Status.UP, indicator.health().getStatus());
    }

    /**
     * §13.11 — 조정 상태가 mutation 을 받을 수 없다고 보고하면 readiness 는 DOWN 이어야 합니다.
     *
     * <p>Redis ping 만 보고 UP 을 주면 catalog mismatch / epoch mismatch / recovery backlog 초과
     * 상태의 Pod 가 계속 mutation 을 받아 그 자리에서 실패합니다.</p>
     */
    @org.junit.jupiter.api.Test
    void coordinationNotReadyMakesReadinessDown() {
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
        when(deployService.targetConcurrency()).thenReturn(4);

        final com.nori.tc.ui.core.coordination.UiCoordinationStatus notReady =
                new com.nori.tc.ui.core.coordination.UiCoordinationStatus() {
                    @Override
                    public boolean coordinationEnabled() {
                        return true;
                    }

                    @Override
                    public com.nori.tc.ui.core.coordination.model.UiMembershipState membershipState() {
                        return com.nori.tc.ui.core.coordination.model.UiMembershipState.FENCED;
                    }

                    @Override
                    public boolean readyForMutation() {
                        return false;
                    }

                    @Override
                    public java.util.Map<String, Object> details() {
                        return java.util.Map.of("membershipState", "FENCED");
                    }
                };

        final UiBackendRuntimeHealthIndicator indicator = new UiBackendRuntimeHealthIndicator(
                deployService,
                deployTraceRegistry,
                repairTaskService,
                notReady,
                gatewayRedisFactory,
                businessRedisFactory
        );

        assertEquals(Status.DOWN, indicator.health().getStatus());
    }

    /**
     * §13.11 / §23.9 — Redis 가 내려가면 readiness 는 <b>DOWN 을 응답</b>해야 하고 예외를 던져서는
     * 안 됩니다.
     *
     * <p>실환경 재현(2026-08-27, 전용 Redis 컨테이너 중단): {@code EqpRepairTaskService.backlogCount()}
     * 가 Redis {@code HVALS} 를 타고, 그 예외가 {@code health()} 밖으로 나가 actuator 가
     * <b>HTTP 500</b> 을 응답했다. §13.11 은 readiness {@code false} 를 요구하고, 500 은 운영자에게
     * "왜 내려갔는지" 를 전혀 알려주지 못한다.</p>
     */
    @org.junit.jupiter.api.Test
    void redisOutageMakesReadinessDownInsteadOfThrowing() {
        final DeployService deployService = mock(DeployService.class);
        final DeployTraceRegistry deployTraceRegistry = mock(DeployTraceRegistry.class);
        final EqpRepairTaskService repairTaskService = mock(EqpRepairTaskService.class);
        final LettuceConnectionFactory gatewayRedisFactory = mock(LettuceConnectionFactory.class);
        final LettuceConnectionFactory businessRedisFactory = mock(LettuceConnectionFactory.class);

        // Redis 가 내려간 상태 — 연결 획득도, repair 잔여 조회도 실패한다.
        when(gatewayRedisFactory.getConnection())
                .thenThrow(new org.springframework.dao.QueryTimeoutException("redis down"));
        when(businessRedisFactory.getConnection())
                .thenThrow(new org.springframework.dao.QueryTimeoutException("redis down"));
        when(repairTaskService.backlogCount())
                .thenThrow(new org.springframework.dao.QueryTimeoutException("redis down"));
        when(deployTraceRegistry.pendingCount()).thenReturn(0);
        when(deployService.targetExecutorActiveCount()).thenReturn(0);
        when(deployService.targetExecutorQueueSize()).thenReturn(0);
        when(deployService.targetConcurrency()).thenReturn(4);

        final UiBackendRuntimeHealthIndicator indicator = new UiBackendRuntimeHealthIndicator(
                deployService,
                deployTraceRegistry,
                repairTaskService,
                com.nori.tc.ui.core.coordination.UiCoordinationStatus.disabled(),
                gatewayRedisFactory,
                businessRedisFactory
        );

        final org.springframework.boot.health.contributor.Health health = indicator.health();
        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("QueryTimeoutException", health.getDetails().get("repairBacklogError"),
                "운영자가 무엇이 실패했는지 알 수 있어야 합니다 — 500 은 그 정보를 지웁니다.");
    }
}
