package com.nori.tc.ui.core.registry;

import com.nori.tc.ui.core.port.redis.DualResponseRedisPort;
import com.nori.tc.ui.domain.task.UiTaskResult;
import com.nori.tc.ui.domain.task.UiTaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DualResponseRegistry}의 완료/정리 경합 방지 동작을 검증합니다.
 */
class DualResponseRegistryTest {

    /**
     * 정상 완료 경로에서 pending 엔트리가 반드시 정리되는지 검증합니다.
     */
    @Test
    @DisplayName("정상 완료 후 pendingCount는 0이어야 한다")
    void 정상완료후_pendingCount_0() throws Exception {
        final InMemoryDualResponseRedisPort redisPort = new InMemoryDualResponseRedisPort();
        final DualResponseRegistry registry = new DualResponseRegistry(redisPort);

        final String traceId = "trace-success-001";
        final CompletableFuture<UiDualTaskFinalResult> future = registry.register(traceId, 3_000L);
        assertTrue(registry.isPending(traceId));

        final UiTaskResult gateway = UiTaskResult.pass(traceId, DualResponseRegistry.SOURCE_GATEWAY);
        final UiTaskResult business = UiTaskResult.pass(traceId, DualResponseRegistry.SOURCE_BUSINESS);
        redisPort.results.put(traceId, new UiDualTaskFinalResult(traceId, true, gateway, business));

        registry.completeFromRedis(traceId);

        final UiDualTaskFinalResult result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result.success());
        assertEquals(0, registry.pendingCount());
        assertFalse(registry.isPending(traceId));
    }

    /**
     * 타임아웃 경로에서 pending 정리와 Redis cancel 호출이 수행되는지 검증합니다.
     */
    @Test
    @DisplayName("타임아웃 후 pendingCount는 0이고 Redis cancel이 호출되어야 한다")
    void 타임아웃후_pendingCount_0_and_redisCancel() {
        final InMemoryDualResponseRedisPort redisPort = new InMemoryDualResponseRedisPort();
        final DualResponseRegistry registry = new DualResponseRegistry(redisPort);

        final String traceId = "trace-timeout-001";
        final CompletableFuture<UiDualTaskFinalResult> future = registry.register(traceId, 50L);

        final ExecutionException executionException = assertThrows(
                ExecutionException.class,
                () -> future.get(2, TimeUnit.SECONDS)
        );
        assertTrue(executionException.getCause() instanceof TimeoutException);

        assertEquals(0, registry.pendingCount());
        assertTrue(redisPort.cancelledTraceIds.contains(traceId));
    }

    /**
     * 취소 경로에서 pending 정리가 보장되는지 검증합니다.
     */
    @Test
    @DisplayName("cancel 호출 후 pendingCount는 0이어야 한다")
    void cancel후_pendingCount_0() {
        final InMemoryDualResponseRedisPort redisPort = new InMemoryDualResponseRedisPort();
        final DualResponseRegistry registry = new DualResponseRegistry(redisPort);

        final String traceId = "trace-cancel-001";
        final CompletableFuture<UiDualTaskFinalResult> future = registry.register(traceId, 3_000L);

        registry.cancel(traceId);

        assertTrue(future.isCancelled());
        assertEquals(0, registry.pendingCount());
        assertTrue(redisPort.cancelledTraceIds.contains(traceId));
    }

    /**
     * 동일 traceId에 완료 신호가 중복 도착해도 단일 완료로 처리되는지 검증합니다.
     */
    @Test
    @DisplayName("중복 completeFromRedis 호출은 단일 완료로 수렴해야 한다")
    void 중복완료_단일완료수렴() throws Exception {
        final InMemoryDualResponseRedisPort redisPort = new InMemoryDualResponseRedisPort();
        final DualResponseRegistry registry = new DualResponseRegistry(redisPort);

        final String traceId = "trace-dup-complete-001";
        final CompletableFuture<UiDualTaskFinalResult> future = registry.register(traceId, 3_000L);

        final UiTaskResult gateway = UiTaskResult.pass(traceId, DualResponseRegistry.SOURCE_GATEWAY);
        final UiTaskResult business = UiTaskResult.pass(traceId, DualResponseRegistry.SOURCE_BUSINESS);
        redisPort.results.put(traceId, new UiDualTaskFinalResult(traceId, true, gateway, business));

        registry.completeFromRedis(traceId);
        registry.completeFromRedis(traceId);

        final UiDualTaskFinalResult result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result.success());
        assertEquals(0, registry.pendingCount());
    }

    /**
     * Gateway -> Business 순서로 PASS 응답이 들어오면 성공 완료되어야 합니다.
     */
    @Test
    @DisplayName("Gateway 이후 Business 응답 순서로 수신되면 PASS로 완료된다")
    void gateway_then_business_순서_성공완료() throws Exception {
        final InMemoryDualResponseRedisPort redisPort = new InMemoryDualResponseRedisPort();
        final DualResponseRegistry registry = new DualResponseRegistry(redisPort);

        final String traceId = "trace-order-gw-bs-001";
        final CompletableFuture<UiDualTaskFinalResult> future = registry.register(traceId, 3_000L);

        registry.record(traceId, DualResponseRegistry.SOURCE_GATEWAY,
                UiTaskResult.pass(traceId, DualResponseRegistry.SOURCE_GATEWAY));
        registry.completeFromRedis(traceId);

        registry.record(traceId, DualResponseRegistry.SOURCE_BUSINESS,
                UiTaskResult.pass(traceId, DualResponseRegistry.SOURCE_BUSINESS));
        registry.completeFromRedis(traceId);

        final UiDualTaskFinalResult result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result.success());
        assertEquals(0, registry.pendingCount());
    }

    /**
     * Business -> Gateway 역순으로 응답이 들어와도 성공 완료되어야 합니다.
     */
    @Test
    @DisplayName("Business 이후 Gateway 역순 응답도 PASS로 완료된다")
    void business_then_gateway_역순_성공완료() throws Exception {
        final InMemoryDualResponseRedisPort redisPort = new InMemoryDualResponseRedisPort();
        final DualResponseRegistry registry = new DualResponseRegistry(redisPort);

        final String traceId = "trace-order-bs-gw-001";
        final CompletableFuture<UiDualTaskFinalResult> future = registry.register(traceId, 3_000L);

        registry.record(traceId, DualResponseRegistry.SOURCE_BUSINESS,
                UiTaskResult.pass(traceId, DualResponseRegistry.SOURCE_BUSINESS));
        registry.completeFromRedis(traceId);

        registry.record(traceId, DualResponseRegistry.SOURCE_GATEWAY,
                UiTaskResult.pass(traceId, DualResponseRegistry.SOURCE_GATEWAY));
        registry.completeFromRedis(traceId);

        final UiDualTaskFinalResult result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result.success());
        assertEquals(0, registry.pendingCount());
    }

    /**
     * 양측 응답 중 하나라도 FAIL이면 최종 결과는 실패여야 합니다.
     */
    @Test
    @DisplayName("양측 응답 중 하나가 FAIL이면 최종 결과는 FAIL이어야 한다")
    void one_side_fail_최종실패() throws Exception {
        final InMemoryDualResponseRedisPort redisPort = new InMemoryDualResponseRedisPort();
        final DualResponseRegistry registry = new DualResponseRegistry(redisPort);

        final String traceId = "trace-fail-001";
        final CompletableFuture<UiDualTaskFinalResult> future = registry.register(traceId, 3_000L);

        registry.record(traceId, DualResponseRegistry.SOURCE_GATEWAY,
                UiTaskResult.pass(traceId, DualResponseRegistry.SOURCE_GATEWAY));
        registry.record(traceId, DualResponseRegistry.SOURCE_BUSINESS,
                UiTaskResult.fail(traceId, DualResponseRegistry.SOURCE_BUSINESS, "BIZ_FAIL", "business failed"));
        registry.completeFromRedis(traceId);

        final UiDualTaskFinalResult result = future.get(1, TimeUnit.SECONDS);
        assertFalse(result.success());
        assertTrue(result.firstFailedResult().isPresent());
        assertEquals("BIZ_FAIL", result.firstFailedResult().get().errorCode());
        assertEquals(0, registry.pendingCount());
    }

    /**
     * 한쪽 응답만 도착하면 타임아웃으로 종료되어야 합니다.
     */
    @Test
    @DisplayName("한쪽 응답만 도착하면 타임아웃 예외로 종료된다")
    void single_side_only_타임아웃() {
        final InMemoryDualResponseRedisPort redisPort = new InMemoryDualResponseRedisPort();
        final DualResponseRegistry registry = new DualResponseRegistry(redisPort);

        final String traceId = "trace-single-side-timeout-001";
        final CompletableFuture<UiDualTaskFinalResult> future = registry.register(traceId, 100L);

        registry.record(traceId, DualResponseRegistry.SOURCE_GATEWAY,
                UiTaskResult.pass(traceId, DualResponseRegistry.SOURCE_GATEWAY));
        registry.completeFromRedis(traceId);

        final ExecutionException executionException = assertThrows(
                ExecutionException.class,
                () -> future.get(2, TimeUnit.SECONDS)
        );
        assertTrue(executionException.getCause() instanceof TimeoutException);
        assertEquals(0, registry.pendingCount());
    }

    /**
     * 다수 traceId를 동시에 처리해도 각각 독립적으로 완료되고 최종 정리가 보장되어야 합니다.
     */
    @Test
    @DisplayName("다수 traceId 동시 처리 시 모두 정상 완료되고 pending이 0이어야 한다")
    void multi_trace_concurrency_정상완료_정리보장() throws Exception {
        final InMemoryDualResponseRedisPort redisPort = new InMemoryDualResponseRedisPort();
        final DualResponseRegistry registry = new DualResponseRegistry(redisPort);

        final int traceCount = 24;
        final ExecutorService executor = Executors.newFixedThreadPool(8);
        final CountDownLatch startGate = new CountDownLatch(1);

        try {
            final List<CompletableFuture<UiDualTaskFinalResult>> futures = new ArrayList<>(traceCount);
            for (int i = 0; i < traceCount; i++) {
                futures.add(registry.register("trace-concurrency-" + i, 3_000L));
            }

            final Future<?>[] tasks = new Future<?>[traceCount * 2];
            int taskIndex = 0;
            for (int i = 0; i < traceCount; i++) {
                final String traceId = "trace-concurrency-" + i;

                tasks[taskIndex++] = executor.submit(() -> {
                    awaitLatch(startGate);
                    registry.record(traceId, DualResponseRegistry.SOURCE_GATEWAY,
                            UiTaskResult.pass(traceId, DualResponseRegistry.SOURCE_GATEWAY));
                    registry.completeFromRedis(traceId);
                });

                tasks[taskIndex++] = executor.submit(() -> {
                    awaitLatch(startGate);
                    registry.record(traceId, DualResponseRegistry.SOURCE_BUSINESS,
                            UiTaskResult.pass(traceId, DualResponseRegistry.SOURCE_BUSINESS));
                    registry.completeFromRedis(traceId);
                });
            }

            startGate.countDown();
            for (Future<?> task : tasks) {
                task.get(2, TimeUnit.SECONDS);
            }

            for (CompletableFuture<UiDualTaskFinalResult> future : futures) {
                final UiDualTaskFinalResult result = future.get(2, TimeUnit.SECONDS);
                assertTrue(result.success());
            }
            assertEquals(0, registry.pendingCount());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * CountDownLatch 대기 중 인터럽트가 발생하면 테스트를 실패 처리합니다.
     *
     * @param latch 동시 시작 게이트
     */
    private static void awaitLatch(final CountDownLatch latch) {
        try {
            latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트 스레드 인터럽트 발생", ex);
        }
    }

    /**
     * 테스트 전용 Redis 포트 인메모리 구현입니다.
     */
    private static final class InMemoryDualResponseRedisPort implements DualResponseRedisPort {

        private final ConcurrentHashMap<String, UiDualTaskFinalResult> results = new ConcurrentHashMap<>();
        private final Set<String> cancelledTraceIds = ConcurrentHashMap.newKeySet();
        private final ConcurrentHashMap<String, UiTaskResult> gatewayResults = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, UiTaskResult> businessResults = new ConcurrentHashMap<>();

        @Override
        public void register(final String traceId, final long timeoutMs) {
            // 테스트에서는 별도 상태를 저장하지 않습니다.
        }

        @Override
        public void record(final String traceId, final String source, final UiTaskResult result) {
            if (DualResponseRegistry.SOURCE_GATEWAY.equals(source)) {
                gatewayResults.put(traceId, result);
            } else if (DualResponseRegistry.SOURCE_BUSINESS.equals(source)) {
                businessResults.put(traceId, result);
            }

            final UiTaskResult gateway = gatewayResults.get(traceId);
            final UiTaskResult business = businessResults.get(traceId);
            if (gateway == null || business == null) {
                return;
            }

            final boolean success = gateway.status() == UiTaskStatus.PASS
                    && business.status() == UiTaskStatus.PASS;
            results.put(traceId, new UiDualTaskFinalResult(traceId, success, gateway, business));
        }

        @Override
        public void cancel(final String traceId) {
            cancelledTraceIds.add(traceId);
            gatewayResults.remove(traceId);
            businessResults.remove(traceId);
            results.remove(traceId);
        }

        @Override
        public Optional<UiDualTaskFinalResult> getResult(final String traceId) {
            return Optional.ofNullable(results.get(traceId));
        }
    }
}
