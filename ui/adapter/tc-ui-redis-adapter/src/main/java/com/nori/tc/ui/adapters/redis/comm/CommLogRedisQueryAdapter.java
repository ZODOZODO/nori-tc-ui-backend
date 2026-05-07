package com.nori.tc.ui.adapters.redis.comm;

import com.nori.tc.db.domain.redis.commlog.RedisCommLogEntry;
import com.nori.tc.ui.core.commlog.CommLogEntry;
import com.nori.tc.ui.core.port.redis.CommLogQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Redis Sorted Set에서 통신 로그를 조회하는 어댑터입니다.
 *
 * <p>키 패턴:</p>
 * <ul>
 *   <li>설비 통신: {@code tc:gateway:comm-log:{eqpId}}</li>
 *   <li>MES 통신: {@code tc:business:comm-log:{eqpId}}</li>
 * </ul>
 *
 * <p>직렬화 정책:</p>
 * <p>적재 측(Gateway/Business)이 typeless {@code GenericJacksonJsonRedisSerializer}로 저장하므로,
 * 조회 측도 동일 직렬화기를 쓰는 {@code commLogRedisTemplate}을 주입받습니다.
 * 역직렬화 결과는 {@link java.util.LinkedHashMap}이므로
 * {@link ObjectMapper#convertValue(Object, Class)}로 {@link RedisCommLogEntry}에 매핑합니다.</p>
 */
@Component
public class CommLogRedisQueryAdapter implements CommLogQueryPort {

    private static final String EQP_KEY_PREFIX = "tc:gateway:comm-log:";
    private static final String MES_KEY_PREFIX = "tc:business:comm-log:";
    private static final Logger log = LoggerFactory.getLogger(CommLogRedisQueryAdapter.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${tc.comm-log.retention-hours:1}")
    private long retentionHours;

    public CommLogRedisQueryAdapter(
            @Qualifier("commLogRedisTemplate") final RedisTemplate<String, Object> redisTemplate,
            @Qualifier("uiRedisObjectMapper") final ObjectMapper objectMapper
    ) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
    }

    @Override
    public List<CommLogEntry> findEqpLogs(final String eqpId) {
        return queryLogs(EQP_KEY_PREFIX + eqpId);
    }

    @Override
    public List<CommLogEntry> findMesLogs(final String eqpId) {
        return queryLogs(MES_KEY_PREFIX + eqpId);
    }

    private List<CommLogEntry> queryLogs(final String key) {
        final long cutoffMs = Instant.now().minus(retentionHours, ChronoUnit.HOURS).toEpochMilli();
        try {
            final Set<Object> values = redisTemplate.opsForZSet()
                    .rangeByScore(key, cutoffMs, Double.MAX_VALUE);
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            final List<CommLogEntry> result = new ArrayList<>(values.size());
            for (final Object value : values) {
                if (value == null) {
                    continue;
                }
                final RedisCommLogEntry entry = objectMapper.convertValue(value, RedisCommLogEntry.class);
                if (entry == null) {
                    continue;
                }
                result.add(new CommLogEntry(
                        entry.getEqpId(),
                        entry.getTimestampMs(),
                        entry.getDirection(),
                        entry.getSource(),
                        entry.getMessageName(),
                        entry.getPayload()
                ));
            }
            return result;
        } catch (Exception ex) {
            log.warn("통신 로그 Redis 조회 실패. key={}", key, ex);
            return List.of();
        }
    }
}
