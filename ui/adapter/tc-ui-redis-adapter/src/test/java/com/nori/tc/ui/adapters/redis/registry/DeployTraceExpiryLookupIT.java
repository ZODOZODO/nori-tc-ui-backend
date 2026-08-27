package com.nori.tc.ui.adapters.redis.registry;

import com.nori.tc.ui.adapters.redis.support.AbstractRedisContainerTest;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.registry.DeployTraceRedisLookupResult;
import com.nori.tc.ui.domain.task.UiTaskStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * deploy step 응답이 유실된 뒤 등록이 TTL 로 사라졌을 때의 조회 결과를 실 Redis 로 고정합니다.
 *
 * <p>이 구분은 mock 으로 증명할 수 없습니다 — 재현해야 하는 실패는 "Redis 가 키를 지웠다" 이고,
 * 그 뒤 조회가 {@code PENDING} 을 돌려주면 호출자는 도착할 수 없는 응답을 영원히 기다립니다.</p>
 */
class DeployTraceExpiryLookupIT extends AbstractRedisContainerTest {

    private static final String KEY_PREFIX = "tc:ui:backend:deploy-trace:";
    private static final String SOURCE = "TC-BUSINESS-APP";

    private static LettuceConnectionFactory connectionFactory;

    private StringRedisTemplate stringRedisTemplate;
    private DeployTraceRedisAdapter adapter;
    private String traceId;

    @BeforeAll
    static void createConnectionFactory() {
        connectionFactory = newConnectionFactory();
    }

    @AfterAll
    static void closeConnectionFactory() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        stringRedisTemplate = new StringRedisTemplate(connectionFactory);
        stringRedisTemplate.afterPropertiesSet();
        final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        adapter = new DeployTraceRedisAdapter(stringRedisTemplate, objectMapper);
        traceId = "DEPLOY-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("등록이 살아 있고 reply 가 덜 모였으면 PENDING 이다")
    void registeredTraceWithMissingReplyIsPending() {
        adapter.register(traceId, Set.of(SOURCE, "TC-GATEWAY-APP"), 60_000L);
        adapter.record(reply());

        assertEquals(DeployTraceRedisLookupResult.Status.PENDING, adapter.getCompletion(traceId).status());
    }

    @Test
    @DisplayName("등록이 TTL 로 사라지면 PENDING 이 아니라 EXPIRED 다")
    void expiredRegistrationIsReportedAsExpired() throws InterruptedException {
        adapter.register(traceId, Set.of(SOURCE), 60_000L);
        // TTL 만료를 기다리는 대신 만료 시각을 앞당긴다. 결과 상태(키 삭제)는 동일하다.
        stringRedisTemplate.expire(KEY_PREFIX + traceId, Duration.ofMillis(50));
        awaitKeyGone();

        assertEquals(DeployTraceRedisLookupResult.Status.EXPIRED, adapter.getCompletion(traceId).status());
    }

    @Test
    @DisplayName("만료 뒤 도착한 reply 는 기록되지 않으므로 여전히 EXPIRED 다")
    void replyArrivingAfterExpiryDoesNotResurrectTheTrace() throws InterruptedException {
        adapter.register(traceId, Set.of(SOURCE), 60_000L);
        stringRedisTemplate.expire(KEY_PREFIX + traceId, Duration.ofMillis(50));
        awaitKeyGone();

        adapter.record(reply());

        assertEquals(DeployTraceRedisLookupResult.Status.EXPIRED, adapter.getCompletion(traceId).status());
    }

    private void awaitKeyGone() throws InterruptedException {
        final String key = KEY_PREFIX + traceId;
        for (int attempt = 0; attempt < 100; attempt++) {
            if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
                return;
            }
            Thread.sleep(20L);
        }
        throw new IllegalStateException("deploy trace 키가 만료되지 않았습니다. key=" + key);
    }

    private UiCommandReply reply() {
        return new UiCommandReply(
                traceId, SOURCE, "EQP_UPDATE_REP", "EQP001", "SOCKET",
                UiTaskStatus.PASS, null, null, null);
    }
}
