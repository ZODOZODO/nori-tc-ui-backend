package com.nori.tc.ui.adapters.redis.registry;

import com.nori.tc.ui.core.deploy.DeployTraceCompletion;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.port.redis.DeployTraceRedisPort;
import com.nori.tc.ui.core.registry.DeployTraceRedisLookupResult;
import com.nori.tc.ui.domain.task.UiTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deploy trace reply 수집 상태를 Redis에 저장하는 어댑터입니다.
 */
@Service
@Primary
public class DeployTraceRedisAdapter implements DeployTraceRedisPort {

    private static final Logger log = LoggerFactory.getLogger(DeployTraceRedisAdapter.class);

    private static final String KEY_PREFIX = "tc:ui:backend:deploy-trace:";
    private static final String FIELD_STATE = "_state";
    private static final String FIELD_EXPECTED_SOURCES = "_expectedSources";
    private static final long REGISTER_TTL_BUFFER_MS = 5_000L;

    public static final String DEPLOY_TRACE_COMPLETE_CHANNEL = "tc:ui:backend:deploy-trace:complete";

    private final RedisTemplate<String, Object> businessRedisTemplate;

    public DeployTraceRedisAdapter(
            @Qualifier("businessRedisTemplate") final RedisTemplate<String, Object> businessRedisTemplate
    ) {
        this.businessRedisTemplate = Objects.requireNonNull(businessRedisTemplate,
                "businessRedisTemplate is null");
    }

    @Override
    public void register(final String traceId, final Set<String> expectedSources, final long timeoutMs) {
        Objects.requireNonNull(traceId, "traceId is null");
        Objects.requireNonNull(expectedSources, "expectedSources is null");

        final String key = buildKey(traceId);
        final long ttlMs = Math.max(1L, timeoutMs + REGISTER_TTL_BUFFER_MS);
        try {
            final HashOperations<String, Object, Object> hashOps = businessRedisTemplate.opsForHash();
            hashOps.putIfAbsent(key, FIELD_STATE, "PENDING");
            hashOps.putIfAbsent(key, FIELD_EXPECTED_SOURCES, List.copyOf(expectedSources));
            businessRedisTemplate.expire(key, Duration.ofMillis(ttlMs));
        } catch (Exception redisException) {
            log.error("deploy trace Redis 등록 실패. traceId={}, key={}", traceId, key, redisException);
            throw redisException;
        }
    }

    @Override
    public void record(final UiCommandReply reply) {
        Objects.requireNonNull(reply, "reply is null");

        final String key = buildKey(reply.traceId());
        final String field = replyField(reply.source());

        try {
            final HashOperations<String, Object, Object> hashOps = businessRedisTemplate.opsForHash();
            final Set<String> expectedSources = readExpectedSources(hashOps.get(key, FIELD_EXPECTED_SOURCES));
            if (expectedSources.isEmpty()) {
                log.debug("대기 중이 아닌 deploy trace reply를 무시합니다. traceId={}, source={}",
                        reply.traceId(), reply.source());
                return;
            }
            if (!expectedSources.contains(reply.source())) {
                log.warn("기대하지 않은 deploy trace source를 무시합니다. traceId={}, source={}, expectedSources={}",
                        reply.traceId(), reply.source(), expectedSources);
                return;
            }

            hashOps.putIfAbsent(key, field, reply);
            if (expectedSources.stream().allMatch(source -> hashOps.hasKey(key, replyField(source)))) {
                businessRedisTemplate.convertAndSend(DEPLOY_TRACE_COMPLETE_CHANNEL, reply.traceId());
            }
        } catch (Exception redisException) {
            log.error("deploy trace Redis 기록 실패. traceId={}, source={}",
                    reply.traceId(), reply.source(), redisException);
            throw redisException;
        }
    }

    @Override
    public void cancel(final String traceId) {
        Objects.requireNonNull(traceId, "traceId is null");

        final String key = buildKey(traceId);
        try {
            businessRedisTemplate.delete(key);
        } catch (Exception redisException) {
            log.warn("deploy trace Redis 정리 실패. traceId={}, key={}", traceId, key, redisException);
        }
    }

    @Override
    public DeployTraceRedisLookupResult getCompletion(final String traceId) {
        Objects.requireNonNull(traceId, "traceId is null");

        final String key = buildKey(traceId);
        try {
            final HashOperations<String, Object, Object> hashOps = businessRedisTemplate.opsForHash();
            final Set<String> expectedSources = readExpectedSources(hashOps.get(key, FIELD_EXPECTED_SOURCES));
            if (expectedSources.isEmpty()) {
                return DeployTraceRedisLookupResult.pending();
            }

            final List<UiCommandReply> replies = new ArrayList<>();
            for (String source : expectedSources) {
                final Object raw = hashOps.get(key, replyField(source));
                if (raw == null) {
                    return DeployTraceRedisLookupResult.pending();
                }
                replies.add(toReply(raw));
            }

            return DeployTraceRedisLookupResult.completed(new DeployTraceCompletion(
                    traceId,
                    expectedSources,
                    replies
            ));
        } catch (Exception redisException) {
            log.error("deploy trace Redis 조회 실패. traceId={}, key={}", traceId, key, redisException);
            return DeployTraceRedisLookupResult.infraError(
                    redisException.getMessage() == null
                            ? "deploy trace Redis 조회 중 내부 오류가 발생했습니다."
                            : redisException.getMessage()
            );
        }
    }

    private Set<String> readExpectedSources(final Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        if (raw instanceof Set<?> set) {
            return set.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        return Set.of();
    }

    private UiCommandReply toReply(final Object raw) {
        if (raw instanceof UiCommandReply reply) {
            return reply;
        }
        if (raw instanceof Map<?, ?> map) {
            return new UiCommandReply(
                    requiredText(map.get("traceId"), "traceId"),
                    requiredText(map.get("source"), "source"),
                    requiredText(map.get("eventType"), "eventType"),
                    requiredText(map.get("eqpId"), "eqpId"),
                    requiredText(map.get("interfaceType"), "interfaceType"),
                    UiTaskStatus.valueOf(requiredText(map.get("status"), "status")),
                    nullableText(map.get("errorCode")),
                    nullableText(map.get("errorMsg"))
            );
        }
        throw new IllegalStateException("지원하지 않는 deploy trace reply 타입입니다. type=" + raw.getClass().getName());
    }

    private static String requiredText(final Object raw, final String fieldName) {
        final String value = nullableText(raw);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(fieldName + " is required");
        }
        return value;
    }

    private static String nullableText(final Object raw) {
        if (raw == null) {
            return null;
        }
        final String text = raw.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static String buildKey(final String traceId) {
        return KEY_PREFIX + traceId;
    }

    private static String replyField(final String source) {
        return "reply:" + source;
    }
}
