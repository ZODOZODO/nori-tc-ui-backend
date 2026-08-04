package com.nori.tc.ui.adapters.redis.dlq;

import com.nori.tc.db.domain.redis.dlq.RedisDlqEntry;
import com.nori.tc.db.domain.redis.quarantine.RedisQuarantineEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 순서 318 회귀 방어 — gateway DLQ·quarantine 의 적재/소비 직렬화 mode 차이를 흡수하는지 검증합니다.
 *
 * <p>적재 측 gateway({@code RedisDlqPublisher} / {@code RedisQuarantineStore})는 common
 * {@code TcRedisCrudRepository} → {@code GenericJacksonJsonRedisSerializer}(plain {@code JsonMapper})로
 * <b>typeless</b> JSON 을 쓰고, 소비 측 {@code gatewayRedisTemplate} 은 {@code RedisSerializer.json()} 으로
 * <b>typed</b> 로 읽는다. 그래서 값이 {@code LinkedHashMap} 으로 들어와 {@code instanceof} 가 false 가 되고,
 * {@code GET /api/dlq/gateway} · {@code /api/dlq/quarantine} 이 항목이 있어도 빈 목록을 반환했다.</p>
 *
 * <p>순서 51(business DLQ)과 동일 근본 원인 · 동일 처방(Map fallback)이다.</p>
 */
class GatewayDlqRedisServiceSerializationModeTest {

    private static final String DLQ_KEY = "tc:gateway:dlq:DLQ-GW-1";
    private static final String QUARANTINE_KEY = "tc:gateway:quarantine:EQP-Q-01";

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> gatewayRedisTemplate = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, Object> valueOps = mock(ValueOperations.class);

    private GatewayDlqRedisService service;

    @BeforeEach
    void setUp() {
        when(gatewayRedisTemplate.opsForValue()).thenReturn(valueOps);
        // 운영 uiRedisObjectMapper 빈과 동일한 구성(UiRedisConfiguration#uiRedisObjectMapper)
        service = new GatewayDlqRedisService(
                gatewayRedisTemplate,
                JsonMapper.builder().findAndAddModules().build()
        );
    }

    @Test
    @DisplayName("순서 318: typeless 로 적재된 gateway DLQ 값(Map)도 항목으로 변환해 반환한다")
    void listAllDlqConvertsTypelessMapValue() {
        final Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("dlqId", "DLQ-GW-1");
        raw.put("equipmentId", "EQP-01");
        raw.put("traceId", "TRACE-1");
        raw.put("commInterfaceType", "SOCKET");
        raw.put("socketType", "TOKENIZED_TEXT");
        raw.put("stage", "INBOUND");
        raw.put("reasonCode", "DECODE_FAIL");
        raw.put("reasonMessage", "프레임 디코드 실패");
        raw.put("occurredAt", 1_780_000_000_000L);
        raw.put("payloadRefKey", "tc:gateway:dlq:payload:DLQ-GW-1");
        raw.put("rawLen", 128L);
        raw.put("b64Len", 172L);
        raw.put("ttlSeconds", 3600L);
        doReturn(List.of(DLQ_KEY)).when(gatewayRedisTemplate).execute(any(RedisCallback.class));
        when(valueOps.get(DLQ_KEY)).thenReturn(raw);

        final List<RedisDlqEntry> entries = service.listAllDlq();

        assertEquals(1, entries.size());
        assertEquals("DLQ-GW-1", entries.getFirst().getDlqId());
        assertEquals("EQP-01", entries.getFirst().getEquipmentId());
        assertEquals("DECODE_FAIL", entries.getFirst().getReasonCode());
    }

    @Test
    @DisplayName("순서 318: typeless 로 적재된 quarantine 값(Map)도 항목으로 변환해 반환한다")
    void listAllQuarantineConvertsTypelessMapValue() {
        final Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("equipmentId", "EQP-Q-01");
        raw.put("reasonCode", "EXT_FAULT");
        raw.put("reasonMessage", "외부 통신 장애 누적");
        raw.put("quarantinedAt", "2026-05-13T01:23:45Z");
        raw.put("ttlSeconds", 3600L);
        doReturn(List.of(QUARANTINE_KEY)).when(gatewayRedisTemplate).execute(any(RedisCallback.class));
        when(valueOps.get(QUARANTINE_KEY)).thenReturn(raw);

        final List<RedisQuarantineEntry> entries = service.listAllQuarantine();

        assertEquals(1, entries.size());
        assertEquals("EQP-Q-01", entries.getFirst().getEquipmentId());
        assertEquals("EXT_FAULT", entries.getFirst().getReasonCode());
        assertEquals(3600L, entries.getFirst().getTtlSeconds());
    }
}
