package com.nori.tc.ui.adapters.redis.dlq;

import com.nori.tc.db.domain.redis.quarantine.RedisQuarantineEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link GatewayDlqRedisService#listAllQuarantine()} 가
 * {@code tc:gateway:quarantine:*} 키 셋을 SCAN 으로 수집한 뒤 각 키 값을
 * {@link RedisQuarantineEntry} 로 캐스팅하여 5 도메인 필드를 그대로 반환하는지
 * 검증합니다.
 *
 * <p>본 모듈에는 Testcontainers / embedded Redis 인프라가 도입돼 있지 않으므로
 * (기존 {@code DeployTracePubSubListenerTest} 등 모두 mock 기반), 동일 컨벤션을
 * 따라 {@link RedisTemplate#execute(RedisCallback)} 의 반환을 직접 stub 합니다.
 * 실제 SCAN 커서 흐름은 GatewayDlqRedisService 의 private {@code scanKeys} 메서드가
 * {@code listAllDlq} 와 공유하므로 본 SPEC 범위에서 별도 회귀 보호는 필요하지 않습니다.
 * 직렬화 호환성은 {@link RedisQuarantineEntry#serialVersionUID} 가 별도로 보장합니다.</p>
 */
class GatewayDlqRedisServiceQuarantineTest {

    private static final String KEY_EQP_Q_01 = "tc:gateway:quarantine:EQP-Q-01";

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> gatewayRedisTemplate = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, Object> valueOps = mock(ValueOperations.class);

    private GatewayDlqRedisService service;

    @BeforeEach
    void setUp() {
        when(gatewayRedisTemplate.opsForValue()).thenReturn(valueOps);
        service = new GatewayDlqRedisService(gatewayRedisTemplate);
    }

    @Test
    void listAllQuarantine_returnsAllStoredEntriesWithDomainFields() {
        final Instant quarantinedAt = Instant.parse("2026-05-13T01:23:45Z");
        final RedisQuarantineEntry entry = new RedisQuarantineEntry(
                "EQP-Q-01",
                "EXT_FAULT",
                "외부 통신 장애 누적",
                quarantinedAt,
                3600L
        );
        doReturn(List.of(KEY_EQP_Q_01)).when(gatewayRedisTemplate).execute(any(RedisCallback.class));
        when(valueOps.get(KEY_EQP_Q_01)).thenReturn(entry);

        final List<RedisQuarantineEntry> entries = service.listAllQuarantine();

        assertEquals(1, entries.size());
        final RedisQuarantineEntry loaded = entries.getFirst();
        assertEquals("EQP-Q-01", loaded.getEquipmentId());
        assertEquals("EXT_FAULT", loaded.getReasonCode());
        assertEquals("외부 통신 장애 누적", loaded.getReasonMessage());
        assertEquals(quarantinedAt, loaded.getQuarantinedAt());
        assertEquals(3600L, loaded.getTtlSeconds());
    }

    @Test
    void listAllQuarantine_returnsEmptyListWhenNoKeys() {
        doReturn(List.of()).when(gatewayRedisTemplate).execute(any(RedisCallback.class));

        final List<RedisQuarantineEntry> entries = service.listAllQuarantine();

        assertTrue(entries.isEmpty());
    }
}
