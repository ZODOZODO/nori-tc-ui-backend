package com.nori.tc.ui.adapters.redis.registry;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.nori.tc.ui.core.deploy.DeployTraceCompletion;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.port.redis.DeployTraceRedisPort;
import com.nori.tc.ui.core.registry.DeployTraceRedisLookupResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deploy trace reply 수집 상태를 Redis에 저장하는 어댑터입니다.
 */
@Service
@Primary
public class DeployTraceRedisAdapter implements DeployTraceRedisPort {

    private static final Logger log = LoggerFactory.getLogger(DeployTraceRedisAdapter.class);

    private static final String KEY_PREFIX = "tc:ui:backend:deploy-trace:";
    private static final String FIELD_META = "_meta";
    private static final long REGISTER_TTL_BUFFER_MS = 5_000L;

    public static final String DEPLOY_TRACE_COMPLETE_CHANNEL = "tc:ui:backend:deploy-trace:complete";

    private final StringRedisTemplate businessStringRedisTemplate;
    private final ObjectMapper uiRedisObjectMapper;

    public DeployTraceRedisAdapter(
            @Qualifier("businessStringRedisTemplate") final StringRedisTemplate businessStringRedisTemplate,
            @Qualifier("uiRedisObjectMapper") final ObjectMapper uiRedisObjectMapper
    ) {
        this.businessStringRedisTemplate = Objects.requireNonNull(businessStringRedisTemplate,
                "businessStringRedisTemplate is null");
        this.uiRedisObjectMapper = Objects.requireNonNull(uiRedisObjectMapper, "uiRedisObjectMapper is null");
    }

    @Override
    public void register(final String traceId, final Set<String> expectedSources, final long timeoutMs) {
        Objects.requireNonNull(traceId, "traceId is null");
        Objects.requireNonNull(expectedSources, "expectedSources is null");

        final String key = buildKey(traceId);
        final long ttlMs = Math.max(1L, timeoutMs + REGISTER_TTL_BUFFER_MS);
        try {
            final HashOperations<String, String, String> hashOps = businessStringRedisTemplate.opsForHash();
            hashOps.putIfAbsent(key, FIELD_META, toJson(new StoredTraceMetadata(traceId, expectedSources)));
            businessStringRedisTemplate.expire(key, Duration.ofMillis(ttlMs));
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
            final HashOperations<String, String, String> hashOps = businessStringRedisTemplate.opsForHash();
            final StoredTraceMetadata metadata = readMetadata(hashOps.get(key, FIELD_META));
            final Set<String> expectedSources = metadata == null ? Set.of() : metadata.expectedSources();
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

            hashOps.putIfAbsent(key, field, toJson(reply));
            if (expectedSources.stream().allMatch(source -> hashOps.hasKey(key, replyField(source)))) {
                businessStringRedisTemplate.convertAndSend(DEPLOY_TRACE_COMPLETE_CHANNEL, reply.traceId());
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
            businessStringRedisTemplate.delete(key);
        } catch (Exception redisException) {
            log.warn("deploy trace Redis 정리 실패. traceId={}, key={}", traceId, key, redisException);
        }
    }

    @Override
    public boolean isRegistered(final String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(businessStringRedisTemplate.hasKey(buildKey(traceId)));
        } catch (Exception redisException) {
            log.warn("deploy trace Redis 등록 여부 조회 실패. traceId={}", traceId, redisException);
            return false;
        }
    }

    @Override
    public DeployTraceRedisLookupResult getCompletion(final String traceId) {
        Objects.requireNonNull(traceId, "traceId is null");

        final String key = buildKey(traceId);
        try {
            final HashOperations<String, String, String> hashOps = businessStringRedisTemplate.opsForHash();
            final StoredTraceMetadata metadata = readMetadata(hashOps.get(key, FIELD_META));
            final Set<String> expectedSources = metadata == null ? Set.of() : metadata.expectedSources();
            if (expectedSources.isEmpty()) {
                return DeployTraceRedisLookupResult.pending();
            }

            final List<UiCommandReply> replies = new ArrayList<>();
            for (String source : expectedSources) {
                final String raw = hashOps.get(key, replyField(source));
                if (raw == null || raw.isBlank()) {
                    return DeployTraceRedisLookupResult.pending();
                }
                replies.add(readReply(raw));
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

    private StoredTraceMetadata readMetadata(final String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        try {
            final StoredTraceMetadata metadata = uiRedisObjectMapper.readValue(rawJson, StoredTraceMetadata.class);
            return new StoredTraceMetadata(
                    metadata.traceId(),
                    metadata.expectedSources().stream()
                            .filter(Objects::nonNull)
                            .map(String::trim)
                            .filter(source -> !source.isBlank())
                            .collect(Collectors.toUnmodifiableSet())
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException("deploy trace metadata 역직렬화에 실패했습니다.", exception);
        }
    }

    private UiCommandReply readReply(final String rawJson) {
        try {
            return uiRedisObjectMapper.readValue(rawJson, UiCommandReply.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("deploy trace reply 역직렬화에 실패했습니다.", exception);
        }
    }

    private String toJson(final Object value) {
        try {
            return uiRedisObjectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("deploy trace JSON 직렬화에 실패했습니다.", exception);
        }
    }

    private static String buildKey(final String traceId) {
        return KEY_PREFIX + traceId;
    }

    private static String replyField(final String source) {
        return "reply:" + source;
    }

    private record StoredTraceMetadata(
            String traceId,
            Set<String> expectedSources
    ) {

        private StoredTraceMetadata {
            traceId = traceId == null ? null : traceId.trim();
            expectedSources = expectedSources == null ? Set.of() : Set.copyOf(expectedSources);
        }
    }
}
