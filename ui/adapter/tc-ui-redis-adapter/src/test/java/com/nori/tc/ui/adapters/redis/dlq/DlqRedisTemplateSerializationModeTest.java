package com.nori.tc.ui.adapters.redis.dlq;

import com.nori.tc.db.domain.redis.dlq.RedisBusinessDlqEntry;
import com.nori.tc.db.domain.redis.dlq.RedisDlqEntry;
import com.nori.tc.ui.adapters.redis.config.UiRedisConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DLQ 조회 경로가 <b>적재 측과 같은 직렬화 mode</b> 의 템플릿을 쓰는지 고정하는 회귀 게이트입니다.
 *
 * <p>왜 이 테스트가 필요한가: 형제 테스트 {@link BusinessDlqRedisServiceSerializationModeTest} 는
 * {@code valueOps.get(key)} 가 {@code LinkedHashMap} 을 <b>돌려준다</b> 고 stub 한다. 그런데 운영에서
 * 실제로 벌어진 일은 그 호출이 <b>예외를 던지는</b> 것이었다 — typed 직렬화기
 * ({@code RedisSerializer.json()})는 {@code @class} 가 없는 값에 {@code Map} 을 돌려주지 않고
 * {@code SerializationException("missing type id property '@class'")} 을 던진다. 그래서 그 mock 은
 * 진짜 실패 시나리오를 재현하지 못했고, 테스트가 통과하는 동안 {@code GET /api/dlq/business} 는
 * 항목이 있어도 빈 목록을 돌려줬다(2026-08-21 운영 로그 실측).</p>
 *
 * <p>이 테스트는 mock 대신 <b>운영 빈이 실제로 쓰는 직렬화기</b>
 * ({@code UiRedisConfiguration#dlqRedisTemplate} 의 value serializer)를 통과시킨다. 서비스가 typed
 * 템플릿으로 되돌아가면 {@link #dlqTemplateSerializer_readsTypelessBytesAsMap()} 과 아래 두 경로 테스트가
 * 함께 깨진다.</p>
 */
class DlqRedisTemplateSerializationModeTest {

    private static final String BUSINESS_KEY = "tc:business:core:dlq:DLQ-1";
    private static final String GATEWAY_KEY = "tc:gateway:dlq:DLQ-2";

    /** 적재 측(common {@code TcDbRedisAutoConfiguration}) 과 동일한 typeless 직렬화기입니다. */
    private static final GenericJacksonJsonRedisSerializer WRITER_SERIALIZER =
            new GenericJacksonJsonRedisSerializer(JsonMapper.builder().findAndAddModules().build());

    /** 운영 {@code dlqRedisTemplate} 빈이 값에 사용하는 직렬화기입니다(설정 파일에서 직접 가져온다). */
    private static final RedisSerializer<?> PRODUCTION_DLQ_SERIALIZER = productionDlqValueSerializer();

    private static RedisSerializer<?> productionDlqValueSerializer() {
        final UiRedisConfiguration configuration = new UiRedisConfiguration();
        // afterPropertiesSet 은 팩토리가 null 이 아니기만 요구한다 — 연결은 시도하지 않는다.
        final RedisTemplate<String, Object> template = configuration.dlqRedisTemplate(
                new LettuceConnectionFactory(),
                configuration.uiRedisObjectMapper()
        );
        return template.getValueSerializer();
    }

    private static RedisBusinessDlqEntry businessEntry() {
        return new RedisBusinessDlqEntry(
                "DLQ-1", "BUSINESS_INBOUND", "PRE_DISPATCH", "INBOUND_MAPPING_REJECTED",
                "data.eqpId is required for WM topic", 1_787_011_739_003L, "wm.tc.events", 1, 4L,
                "UNKNOWN_EQP", "WM", "EQP_CONDITION_CHECK", "UNKNOWN_TRACE",
                "wm.tc.events:1:4", Map.of("flow", "WM"), 3_600L
        );
    }

    private static RedisDlqEntry gatewayEntry() {
        return new RedisDlqEntry(
                "DLQ-2", "EQP-01", "trace-2", "SECS", null, "DECODE", "DESERIALIZE_FAIL",
                "payload 파싱 실패", 1_787_011_739_004L, "tc:gateway:dlq:payload:DLQ-2",
                128L, 172L, Map.of("k", "v"), 3_600L
        );
    }

    @Test
    @DisplayName("dlqRedisTemplate 의 직렬화기는 적재 측 typeless 바이트를 Map 으로 읽는다 — typed 는 같은 바이트에서 예외다")
    void dlqTemplateSerializer_readsTypelessBytesAsMap() {
        final byte[] storedBytes = WRITER_SERIALIZER.serialize(businessEntry());

        final Object readByProduction = PRODUCTION_DLQ_SERIALIZER.deserialize(storedBytes);
        assertInstanceOf(Map.class, readByProduction,
                "운영 dlqRedisTemplate 직렬화기가 typeless 적재 값을 Map 으로 읽지 못했습니다");

        // 회귀 증인 — 이 단언이 없으면 위 단언이 "typed 도 통과하는" 무력한 검사인지 구분할 수 없다.
        final RedisSerializer<Object> typedSerializer = RedisSerializer.json();
        assertThrows(Exception.class, () -> typedSerializer.deserialize(storedBytes),
                "typed 직렬화기가 @class 없는 값을 읽어버렸습니다 — 이 테스트의 전제가 무너졌으니 재작성이 필요합니다");
    }

    @Test
    @DisplayName("Business DLQ 조회는 적재 측 typeless 값을 항목으로 돌려준다(운영 직렬화기 경유)")
    @SuppressWarnings("unchecked")
    void businessDlq_returnsEntryThroughProductionSerializer() {
        final byte[] storedBytes = WRITER_SERIALIZER.serialize(businessEntry());

        final RedisTemplate<String, Object> template = mock(RedisTemplate.class);
        final ValueOperations<String, Object> valueOps = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOps);
        doReturn(List.of(BUSINESS_KEY)).when(template).execute(any(RedisCallback.class));
        // 손으로 만든 Map 이 아니라 운영 직렬화기가 실제로 내놓는 값을 준다.
        when(valueOps.get(BUSINESS_KEY))
                .thenAnswer(invocation -> PRODUCTION_DLQ_SERIALIZER.deserialize(storedBytes));

        final List<RedisBusinessDlqEntry> entries = new BusinessDlqRedisService(
                template, JsonMapper.builder().findAndAddModules().build()
        ).listAllDlq();

        assertEquals(1, entries.size(),
                "적재 측 포맷 그대로의 값이 조회에서 탈락했습니다 — DLQ 화면이 항목을 잃습니다");
        assertEquals("DLQ-1", entries.getFirst().getDlqId());
        assertEquals("INBOUND_MAPPING_REJECTED", entries.getFirst().getReasonCode());
    }

    @Test
    @DisplayName("Gateway DLQ 조회도 같은 템플릿·같은 mode 로 항목을 돌려준다")
    @SuppressWarnings("unchecked")
    void gatewayDlq_returnsEntryThroughProductionSerializer() {
        final byte[] storedBytes = WRITER_SERIALIZER.serialize(gatewayEntry());

        final RedisTemplate<String, Object> template = mock(RedisTemplate.class);
        final ValueOperations<String, Object> valueOps = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOps);
        doReturn(List.of(GATEWAY_KEY)).when(template).execute(any(RedisCallback.class));
        when(valueOps.get(GATEWAY_KEY))
                .thenAnswer(invocation -> PRODUCTION_DLQ_SERIALIZER.deserialize(storedBytes));

        final List<RedisDlqEntry> entries = new GatewayDlqRedisService(
                template, JsonMapper.builder().findAndAddModules().build()
        ).listAllDlq();

        assertEquals(1, entries.size(),
                "적재 측 포맷 그대로의 값이 조회에서 탈락했습니다 — DLQ 화면이 항목을 잃습니다");
        assertEquals("DLQ-2", entries.getFirst().getDlqId());
        assertEquals("EQP-01", entries.getFirst().getEquipmentId());
    }
}
