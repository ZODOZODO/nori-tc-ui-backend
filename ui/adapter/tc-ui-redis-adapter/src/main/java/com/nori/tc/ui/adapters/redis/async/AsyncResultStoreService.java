package com.nori.tc.ui.adapters.redis.async;

import tools.jackson.databind.ObjectMapper;
import com.nori.tc.ui.core.model.AsyncResultEntry;
import com.nori.tc.ui.core.model.AsyncStatus;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.port.redis.AsyncResultStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Business Redis 논리 연결을 이용한 비동기 작업 결과 저장 서비스입니다.
 *
 * <p>{@link AsyncResultStorePort}를 구현하며, eqp_start/eqp_end 요청에 대한
 * gateway 응답(tc.ui.commands 수신)을 임시 저장합니다.</p>
 *
 * <p>흐름:</p>
 * <ol>
 *   <li>Front → POST /api/eqp/{id}/start — UI Backend가 202 즉시 반환</li>
 *   <li>Gateway가 tc.ui.commands에 EQP_START_REP 메시지 발행</li>
 *   <li>{@code UiCommandIngressService}가 이 서비스의 {@code markCompleted()}를 호출하여 결과 저장</li>
 *   <li>Front → GET /api/async/{traceId} — polling으로 결과 조회</li>
 * </ol>
 *
 * <p>키 형식: {@code tc:ui:backend:async:{traceId}}</p>
 * <p>TTL: {@code tc.ui.backend.async.result-ttl-seconds} 설정값 (기본 600초)</p>
 *
 * <p>{@link UiCommandReply}는 Redis 저장 시 {@link RedisUiAsyncResultEntry}로 변환하여
 * JSON 직렬화 방식으로 저장합니다.</p>
 */
@Service
@EnableConfigurationProperties(UiAsyncProperties.class)
public class AsyncResultStoreService implements AsyncResultStorePort {

    private static final Logger log = LoggerFactory.getLogger(AsyncResultStoreService.class);

    /** Redis 비동기 결과 키 접두사 */
    private static final String KEY_PREFIX = "tc:ui:backend:async:";

    /**
     * PENDING 상태 타임아웃 전환 시 버퍼(ms)입니다.
     *
     * <p>Gateway 응답이 타임아웃 경계 직전에 도착하는 경우를 완충하기 위해
     * timeoutMs에 버퍼를 더해 TIMEOUT 전환 시점을 계산합니다.</p>
     */
    private static final long TIMEOUT_BUFFER_MS = 5_000L;

    private final RedisTemplate<String, Object> businessRedisTemplate;
    private final UiAsyncProperties asyncProperties;
    private final ObjectMapper redisObjectMapper;

    /**
     * 현재 인스턴스에서 등록한 PENDING traceId의 타임아웃 예정 시각(epoch ms) 캐시입니다.
     *
     * <p>분산 상태의 정답은 Redis이며, 이 맵은 스케줄러 반복 조회 비용을 줄이기 위한
     * 로컬 힌트 용도로만 사용합니다.</p>
     */
    private final Map<String, Long> pendingTimeoutEpochByTraceId = new ConcurrentHashMap<>();

    public AsyncResultStoreService(
            @Qualifier("businessRedisTemplate") final RedisTemplate<String, Object> businessRedisTemplate,
            final UiAsyncProperties asyncProperties,
            @Qualifier("uiRedisObjectMapper") final ObjectMapper redisObjectMapper
    ) {
        this.businessRedisTemplate = Objects.requireNonNull(businessRedisTemplate,
                "businessRedisTemplate 은 null 이 될 수 없습니다.");
        this.asyncProperties = Objects.requireNonNull(asyncProperties,
                "asyncProperties 는 null 이 될 수 없습니다.");
        this.redisObjectMapper = Objects.requireNonNull(redisObjectMapper,
                "redisObjectMapper 는 null 이 될 수 없습니다.");
    }

    /**
     * 비동기 작업 대기(PENDING) 상태를 등록합니다.
     *
     * <p>EqpController가 EQP_START / EQP_END 발행 직전에 호출하며,
     * 응답이 도착하기 전 polling 요청에서 "존재하지 않는 traceId(404)"로 오인하지 않도록
     * 먼저 상태 키를 생성합니다.</p>
     *
     * @param traceId 작업 추적 ID (ULID, 캐시 키 생성에 사용)
     * @param timeoutMs 비동기 처리 타임아웃(ms)
     */
    @Override
    public void registerPending(final String traceId, final long timeoutMs) {
        Objects.requireNonNull(traceId, "traceId 는 null 이 될 수 없습니다.");
        if (timeoutMs <= 0L) {
            throw new IllegalArgumentException("timeoutMs 는 0보다 커야 합니다.");
        }

        final String key = KEY_PREFIX + traceId;
        final long ttlSeconds = asyncProperties.getResultTtlSeconds();
        final long timeoutAtEpochMs = System.currentTimeMillis() + timeoutMs + TIMEOUT_BUFFER_MS;
        final RedisUiAsyncResultEntry entry = RedisUiAsyncResultEntry.pending(traceId, timeoutAtEpochMs);

        try {
            writeWithConfiguredTtl(key, entry, ttlSeconds);
            pendingTimeoutEpochByTraceId.put(traceId, timeoutAtEpochMs);
            log.debug("비동기 결과 PENDING 등록. traceId={}, timeoutMs={}, timeoutAtEpochMs={}",
                    traceId, timeoutMs, timeoutAtEpochMs);
        } catch (Exception e) {
            log.error("비동기 결과 PENDING 등록 실패. traceId={}, timeoutMs={}", traceId, timeoutMs, e);
            throw e;
        }
    }

    /**
     * 비동기 작업 완료(COMPLETED) 상태를 저장합니다.
     *
     * <p>Gateway 응답 도착 시 UiCommandIngressService가 호출합니다.</p>
     *
     * @param traceId 작업 추적 ID
     * @param reply 완료 응답 payload
     */
    @Override
    public void markCompleted(final String traceId, final UiCommandReply reply) {
        Objects.requireNonNull(traceId, "traceId 는 null 이 될 수 없습니다.");
        Objects.requireNonNull(reply, "reply 는 null 이 될 수 없습니다.");

        final String key = KEY_PREFIX + traceId;
        final RedisUiAsyncResultEntry entry = RedisUiAsyncResultEntry.completed(reply);
        final long ttlSeconds = asyncProperties.getResultTtlSeconds();

        try {
            writeWithConfiguredTtl(key, entry, ttlSeconds);
            pendingTimeoutEpochByTraceId.remove(traceId);
            log.info("비동기 결과 COMPLETED 저장. traceId={}, eqpId={}, status={}",
                    traceId, reply.eqpId(), reply.status());
        } catch (Exception e) {
            log.error("비동기 결과 COMPLETED 저장 실패. traceId={}, eqpId={}", traceId, reply.eqpId(), e);
            throw e;
        }
    }

    /**
     * 비동기 작업을 TIMEOUT 상태로 전환합니다.
     *
     * <p>이미 COMPLETED 상태인 경우에는 덮어쓰지 않고 종료합니다.</p>
     *
     * @param traceId 작업 추적 ID
     */
    @Override
    public void markTimeout(final String traceId) {
        Objects.requireNonNull(traceId, "traceId 는 null 이 될 수 없습니다.");

        final String key = KEY_PREFIX + traceId;
        final boolean applied = Boolean.TRUE.equals(businessRedisTemplate.execute(
                new SessionCallback<Boolean>() {
                    @Override
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    public Boolean execute(final RedisOperations operations) {
                        return applyTimeoutIfNotCompleted(operations, key, traceId);
                    }
                }));

        pendingTimeoutEpochByTraceId.remove(traceId);
        if (applied) {
            log.warn("비동기 결과 TIMEOUT 전환. traceId={}", traceId);
        } else if (log.isDebugEnabled()) {
            log.debug("TIMEOUT 전환 생략 - 상태 부재 또는 이미 COMPLETED. traceId={}", traceId);
        }
    }

    /**
     * COMPLETED 가 아닐 때만 TIMEOUT 을 기록합니다 — WATCH/MULTI 로 read-check-write 를 원자화합니다.
     *
     * <p>비원자였을 때: 타임아웃 경계에서 스케줄러가 PENDING 을 읽은 직후 gateway PASS 응답이 COMPLETED 를
     * 쓰면, 스케줄러가 그 위에 TIMEOUT 을 덮어써 <b>성공한 EQP_START 가 프런트에 TIMEOUT 으로 보였다</b>
     * (S08 D-8). {@code TIMEOUT_BUFFER_MS} 는 만료 시점만 늦출 뿐 read↔write 창을 없애지 못한다.</p>
     *
     * <p>WATCH 후 값이 바뀌면 EXEC 가 비어 반환되고 쓰기는 적용되지 않는다 —
     * 경쟁자가 쓴 COMPLETED 를 보존하는 것이 정확한 결과이므로 재시도하지 않는다.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Boolean applyTimeoutIfNotCompleted(
            final RedisOperations operations,
            final String key,
            final String traceId
    ) {
        operations.watch(key);

        final Optional<RedisUiAsyncResultEntry> existingOpt = readRedisEntry(operations, key);
        if (existingOpt.isEmpty()
                || AsyncStatus.COMPLETED.name().equals(existingOpt.get().getAsyncStatus())) {
            operations.unwatch();
            return Boolean.FALSE;
        }

        operations.multi();
        writeWithConfiguredTtl(operations, key, RedisUiAsyncResultEntry.timeout(traceId),
                asyncProperties.getResultTtlSeconds());
        final List<Object> results = operations.exec();
        return results != null && !results.isEmpty();
    }

    /**
     * 비동기 작업 상태를 조회합니다.
     *
     * <p>PENDING 상태가 로컬 계산상 만료 시각을 넘긴 경우 즉시 TIMEOUT으로 승격하여 반환합니다.</p>
     *
     * @param traceId 조회 대상 traceId
     * @return 상태 엔트리
     */
    @Override
    public Optional<AsyncResultEntry> getWithStatus(final String traceId) {
        Objects.requireNonNull(traceId, "traceId 는 null 이 될 수 없습니다.");

        final String key = KEY_PREFIX + traceId;
        try {
            final Optional<RedisUiAsyncResultEntry> entryOpt = readRedisEntry(key);
            if (entryOpt.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("비동기 상태 조회 결과 없음. traceId={}, key={}", traceId, key);
                }
                return Optional.empty();
            }

            final RedisUiAsyncResultEntry entry = entryOpt.get();

            // PENDING + 기한 초과면 조회 시점에 즉시 TIMEOUT 승격(스케줄러 누락 대비)
            if (entry.isPending() && entry.getTimeoutAtEpochMs() > 0L
                    && System.currentTimeMillis() >= entry.getTimeoutAtEpochMs()) {
                markTimeout(traceId);
                return Optional.of(AsyncResultEntry.timeout(traceId));
            }

            final AsyncResultEntry result = entry.toAsyncResultEntry();
            if (log.isDebugEnabled()) {
                log.debug("비동기 상태 조회 성공. traceId={}, asyncStatus={}", traceId, result.status());
            }
            return Optional.of(result);

        } catch (Exception e) {
            log.warn("비동기 상태 조회 실패. traceId={}, key={}", traceId, key, e);
            return Optional.empty();
        }
    }

    /**
     * 로컬 pending 맵을 기준으로 만료된 traceId를 주기적으로 TIMEOUT 전환합니다.
     *
     * <p>스케줄러 누락/재시작 등으로 전환이 지연되어도 {@link #getWithStatus(String)}의
     * 조회 시 승격 로직이 보조 안전장치로 동작합니다.</p>
     */
    @Scheduled(fixedDelayString = "${tc.ui.backend.async.timeout-scan-interval-ms:1000}")
    public void promoteExpiredPendingToTimeout() {
        final long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> pending : pendingTimeoutEpochByTraceId.entrySet()) {
            final String traceId = pending.getKey();
            final Long timeoutAt = pending.getValue();
            if (timeoutAt != null && now >= timeoutAt) {
                try {
                    markTimeout(traceId);
                } catch (Exception e) {
                    log.error("비동기 TIMEOUT 스케줄 전환 실패. traceId={}", traceId, e);
                }
            }
        }
    }

    /**
     * Redis 키에 엔트리를 설정합니다.
     *
     * @param key Redis 키
     * @param entry 저장 엔트리
     * @param ttlSeconds TTL(초). 0이면 무기한
     */
    private void writeWithConfiguredTtl(
            final String key,
            final RedisUiAsyncResultEntry entry,
            final long ttlSeconds
    ) {
        writeWithConfiguredTtl(businessRedisTemplate, key, entry, ttlSeconds);
    }

    /**
     * 지정한 Redis 연산 컨텍스트에 엔트리를 설정합니다(트랜잭션 세션 내 사용 포함).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void writeWithConfiguredTtl(
            final RedisOperations operations,
            final String key,
            final RedisUiAsyncResultEntry entry,
            final long ttlSeconds
    ) {
        if (ttlSeconds > 0L) {
            operations.opsForValue().set(key, entry, Duration.ofSeconds(ttlSeconds));
            return;
        }
        operations.opsForValue().set(key, entry);
    }

    /**
     * Redis 값을 {@link RedisUiAsyncResultEntry}로 읽습니다.
     *
     * @param key Redis 키
     * @return 파싱 성공 시 Optional 엔트리
     */
    private Optional<RedisUiAsyncResultEntry> readRedisEntry(final String key) {
        return readRedisEntry(businessRedisTemplate, key);
    }

    /**
     * 지정한 Redis 연산 컨텍스트에서 값을 읽습니다.
     *
     * <p>WATCH 는 같은 연결에서 수행한 읽기에만 유효하므로, 세션 안에서는 반드시 세션의
     * {@code RedisOperations} 로 읽어야 합니다.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Optional<RedisUiAsyncResultEntry> readRedisEntry(
            final RedisOperations operations,
            final String key
    ) {
        final Object raw = operations.opsForValue().get(key);
        if (raw == null) {
            return Optional.empty();
        }

        if (raw instanceof RedisUiAsyncResultEntry entry) {
            return Optional.of(entry);
        }

        if (raw instanceof Map<?, ?> map) {
            // 직렬화 포맷 전환 과정에서 LinkedHashMap으로 역직렬화되는 경우를 방어합니다.
            final RedisUiAsyncResultEntry converted = redisObjectMapper.convertValue(map, RedisUiAsyncResultEntry.class);
            return Optional.of(converted);
        }

        log.warn("비동기 결과 타입 불일치, 해당 항목 제거. key={}, actualType={}",
                key, raw.getClass().getName());
        operations.delete(key);
        return Optional.empty();
    }
}
