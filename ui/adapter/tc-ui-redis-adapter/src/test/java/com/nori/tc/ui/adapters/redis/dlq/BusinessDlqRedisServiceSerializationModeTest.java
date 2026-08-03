package com.nori.tc.ui.adapters.redis.dlq;

import com.nori.tc.db.domain.redis.dlq.RedisBusinessDlqEntry;
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
 * S08 D-18 / S07 MISSED-BIZ-D-6 회귀 방어 — 적재 측(business, common typeless JSON)과
 * 소비 측({@code RedisSerializer.json()}, typed) 의 직렬화 mode 차이로 값이 {@code LinkedHashMap} 으로
 * 들어오면 {@code instanceof} 가 false 가 되어 {@code GET /api/dlq/business} 가 항목이 있어도
 * 빈 목록을 반환하던 결함을 검증한다.
 *
 * <p>본 모듈은 Testcontainers / embedded Redis 없이 mock 기반 컨벤션을 따른다
 * ({@code GatewayDlqRedisServiceQuarantineTest} 와 동일).</p>
 */
class BusinessDlqRedisServiceSerializationModeTest {

    private static final String KEY = "tc:business:core:dlq:DLQ-1";

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> businessRedisTemplate = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, Object> valueOps = mock(ValueOperations.class);

    private BusinessDlqRedisService service;

    @BeforeEach
    void setUp() {
        when(businessRedisTemplate.opsForValue()).thenReturn(valueOps);
        // 운영 uiRedisObjectMapper 빈과 동일한 구성(UiRedisConfiguration#uiRedisObjectMapper)
        service = new BusinessDlqRedisService(
                businessRedisTemplate,
                JsonMapper.builder().findAndAddModules().build()
        );
    }

    @Test
    @DisplayName("typeless 로 적재된 값(Map)도 DLQ 항목으로 변환해 반환한다")
    void listAllDlq_convertsTypelessMapValue() {
        final Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("dlqId", "DLQ-1");
        raw.put("source", "TC-BUSINESS-CORE");
        raw.put("stage", "CONSUME");
        raw.put("reasonCode", "DESERIALIZE_FAIL");
        raw.put("reasonMessage", "payload 파싱 실패");
        raw.put("occurredAt", 1_780_000_000_000L);
        raw.put("topic", "tc.mes.commands");
        raw.put("partition", 2);
        raw.put("offset", 41L);
        raw.put("eqpId", "EQP-01");
        raw.put("messageType", "EQP_START");
        raw.put("messageName", "start");
        raw.put("traceId", "trace-1");
        raw.put("payloadRef", "tc:business:core:dlq:payload:DLQ-1");
        raw.put("tags", Map.of("k", "v"));
        raw.put("ttlSeconds", 3_600L);

        doReturn(List.of(KEY)).when(businessRedisTemplate).execute(any(RedisCallback.class));
        when(valueOps.get(KEY)).thenReturn(raw);

        final List<RedisBusinessDlqEntry> entries = service.listAllDlq();

        assertEquals(1, entries.size(), "typeless 적재 값이 스킵되어 빈 목록이 반환됐습니다");
        final RedisBusinessDlqEntry loaded = entries.getFirst();
        assertEquals("DLQ-1", loaded.getDlqId());
        assertEquals("CONSUME", loaded.getStage());
        assertEquals("DESERIALIZE_FAIL", loaded.getReasonCode());
        assertEquals(1_780_000_000_000L, loaded.getOccurredAt());
        assertEquals("EQP-01", loaded.getEqpId());
        assertEquals("trace-1", loaded.getTraceId());
    }

    @Test
    @DisplayName("typed 로 적재된 도메인 타입 값은 그대로 반환한다(기존 계약 보존)")
    void listAllDlq_keepsTypedDomainValue() {
        final RedisBusinessDlqEntry entry = new RedisBusinessDlqEntry(
                "DLQ-2", "TC-BUSINESS-CORE", "EXECUTE", "TIMEOUT", "처리 시간 초과",
                1_780_000_000_001L, "tc.mes.commands", 1, 7L, "EQP-02",
                "EQP_END", "end", "trace-2", null, Map.of(), null
        );

        doReturn(List.of(KEY)).when(businessRedisTemplate).execute(any(RedisCallback.class));
        when(valueOps.get(KEY)).thenReturn(entry);

        final List<RedisBusinessDlqEntry> entries = service.listAllDlq();

        assertEquals(1, entries.size());
        assertEquals("DLQ-2", entries.getFirst().getDlqId());
    }
}
