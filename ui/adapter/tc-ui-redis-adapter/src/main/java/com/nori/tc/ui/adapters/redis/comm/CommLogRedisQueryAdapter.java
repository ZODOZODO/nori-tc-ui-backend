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
 */
@Component
public class CommLogRedisQueryAdapter implements CommLogQueryPort {

    private static final String EQP_KEY_PREFIX = "tc:gateway:comm-log:";
    private static final String MES_KEY_PREFIX = "tc:business:comm-log:";
    private static final Logger log = LoggerFactory.getLogger(CommLogRedisQueryAdapter.class);

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${tc.comm-log.retention-hours:1}")
    private long retentionHours;

    public CommLogRedisQueryAdapter(
            @Qualifier("gatewayRedisTemplate") final RedisTemplate<String, Object> redisTemplate
    ) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate is null");
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
                if (value instanceof RedisCommLogEntry entry) {
                    result.add(new CommLogEntry(
                            entry.getEqpId(),
                            entry.getTimestampMs(),
                            entry.getDirection(),
                            entry.getSource(),
                            entry.getMessageName(),
                            entry.getPayload()
                    ));
                }
            }
            return result;
        } catch (Exception ex) {
            log.warn("통신 로그 Redis 조회 실패. key={}", key, ex);
            return List.of();
        }
    }
}
