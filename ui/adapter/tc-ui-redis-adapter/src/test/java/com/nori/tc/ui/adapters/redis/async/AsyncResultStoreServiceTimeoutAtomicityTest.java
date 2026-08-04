package com.nori.tc.ui.adapters.redis.async;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S08 D-8 — TIMEOUT 전환의 read-check-write 가 원자적인지 검증합니다.
 *
 * <p>비원자였을 때: 타임아웃 경계에서 {@code promoteExpiredPendingToTimeout} 스케줄러가 PENDING 을
 * 읽은 직후 gateway PASS 응답이 COMPLETED 를 쓰면, 스케줄러가 그 위에 TIMEOUT 을 덮어써
 * 성공한 EQP_START 가 프런트에 TIMEOUT 으로 보였다.</p>
 *
 * <p>본 모듈은 Testcontainers / embedded Redis 없이 mock 기반 컨벤션을 따른다
 * ({@code BusinessDlqRedisServiceSerializationModeTest} 와 동일). 여기서는 CAS 프로토콜
 * (WATCH → 읽기 → MULTI → 쓰기 → EXEC) 자체를 검증한다.</p>
 */
class AsyncResultStoreServiceTimeoutAtomicityTest {

    private static final String TRACE_ID = "TRACE-1";
    private static final String KEY = "tc:ui:backend:async:" + TRACE_ID;

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> businessRedisTemplate = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final RedisOperations<String, Object> sessionOperations = mock(RedisOperations.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, Object> sessionValueOps = mock(ValueOperations.class);

    private AsyncResultStoreService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        final UiAsyncProperties asyncProperties = new UiAsyncProperties();
        service = new AsyncResultStoreService(
                businessRedisTemplate,
                asyncProperties,
                JsonMapper.builder().findAndAddModules().build()
        );

        when(sessionOperations.opsForValue()).thenReturn(sessionValueOps);
        // execute(SessionCallback) 는 실제 구현처럼 콜백에 세션 컨텍스트를 넘긴다.
        when(businessRedisTemplate.execute(any(SessionCallback.class))).thenAnswer(invocation -> {
            final SessionCallback<Object> callback = invocation.getArgument(0);
            return callback.execute(sessionOperations);
        });
    }

    @Test
    @DisplayName("S08 D-8: PENDING 이면 WATCH → 읽기 → MULTI → 쓰기 → EXEC 순서로 원자 전환합니다")
    void markTimeoutAppliesWriteInsideWatchedTransaction() {
        when(sessionValueOps.get(KEY)).thenReturn(RedisUiAsyncResultEntry.pending(TRACE_ID, 1L));
        when(sessionOperations.exec()).thenReturn(List.of(Boolean.TRUE));

        service.markTimeout(TRACE_ID);

        final var ordered = inOrder(sessionOperations, sessionValueOps);
        ordered.verify(sessionOperations).watch(KEY);
        ordered.verify(sessionValueOps).get(KEY);
        ordered.verify(sessionOperations).multi();
        ordered.verify(sessionValueOps).set(eq(KEY), any(), any(Duration.class));
        ordered.verify(sessionOperations).exec();
    }

    @Test
    @DisplayName("S08 D-8: 이미 COMPLETED 면 WATCH 를 해제하고 쓰지 않습니다")
    void markTimeoutSkipsWriteWhenAlreadyCompleted() {
        when(sessionValueOps.get(KEY)).thenReturn(RedisUiAsyncResultEntry.completed(
                new com.nori.tc.ui.core.model.UiCommandReply(
                        TRACE_ID, "TC-UI-APP", "EQP_START_REP", "EQP-01", "SOCKET",
                        com.nori.tc.ui.domain.task.UiTaskStatus.PASS, null, null)));

        service.markTimeout(TRACE_ID);

        verify(sessionOperations).watch(KEY);
        verify(sessionOperations).unwatch();
        verify(sessionOperations, never()).multi();
        verify(sessionValueOps, never()).set(eq(KEY), any(), any(Duration.class));
    }

}
