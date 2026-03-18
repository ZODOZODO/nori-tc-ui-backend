package com.nori.tc.ui.adapters.redis.registry;

import com.nori.tc.ui.core.port.redis.DualResponseRedisPort;
import com.nori.tc.ui.core.registry.DualResponseRegistry;
import com.nori.tc.ui.core.registry.UiDualTaskFinalResult;
import com.nori.tc.ui.domain.task.UiTaskResult;
import com.nori.tc.ui.domain.task.UiTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * DualResponse 분산 상태를 Redis에 저장/조회하는 어댑터입니다.
 *
 * <p>저장 구조:</p>
 * <ul>
 *   <li>Key: {@code tc:ui:backend:dual:{traceId}}</li>
 *   <li>Field: {@code gateway} / {@code business}</li>
 *   <li>Value: {@link UiTaskResult} JSON</li>
 * </ul>
 *
 * <p>완료 알림:</p>
 * <p>양쪽 응답이 모두 기록되면 {@link #DUAL_COMPLETE_CHANNEL} 채널에 traceId를 발행합니다.
 * 해당 신호를 구독한 인스턴스가 로컬 Future를 완료합니다.</p>
 */
@Service
public class DualResponseRedisAdapter implements DualResponseRedisPort {

    private static final Logger log = LoggerFactory.getLogger(DualResponseRedisAdapter.class);

    /** DualResponse Redis Key prefix */
    private static final String KEY_PREFIX = "tc:ui:backend:dual:";

    /** Gateway 결과 저장 field */
    private static final String FIELD_GATEWAY = "gateway";

    /** Business 결과 저장 field */
    private static final String FIELD_BUSINESS = "business";

    /** 초기 등록 시 상태 식별 field (운영 추적용) */
    private static final String FIELD_STATE = "_state";

    /** DualResponse 완료 Pub/Sub 채널명 */
    public static final String DUAL_COMPLETE_CHANNEL = "tc:ui:backend:dual:complete";

    /** 등록 시 기본 여유 TTL(ms) */
    private static final long REGISTER_TTL_BUFFER_MS = 5_000L;

    private final RedisTemplate<String, Object> businessRedisTemplate;

    public DualResponseRedisAdapter(
            @Qualifier("businessRedisTemplate") final RedisTemplate<String, Object> businessRedisTemplate
    ) {
        this.businessRedisTemplate = Objects.requireNonNull(businessRedisTemplate,
                "businessRedisTemplate is null");
    }

    /**
     * traceId 상태를 Redis Hash에 등록하고 TTL을 설정합니다.
     *
     * @param traceId traceId
     * @param timeoutMs DualResponse 타임아웃(ms)
     */
    @Override
    public void register(final String traceId, final long timeoutMs) {
        Objects.requireNonNull(traceId, "traceId is null");

        final String key = buildKey(traceId);
        final long ttlMs = Math.max(1L, timeoutMs + REGISTER_TTL_BUFFER_MS);

        try {
            final HashOperations<String, Object, Object> hashOps = businessRedisTemplate.opsForHash();
            hashOps.putIfAbsent(key, FIELD_STATE, "PENDING");
            businessRedisTemplate.expire(key, Duration.ofMillis(ttlMs));

            log.debug("DualResponse Redis 등록 완료. traceId={}, key={}, ttlMs={}",
                    traceId, key, ttlMs);
        } catch (Exception e) {
            log.error("DualResponse Redis 등록 실패. traceId={}, key={}", traceId, key, e);
            throw e;
        }
    }

    /**
     * 수신 응답을 Redis Hash에 기록하고 완료 여부를 판단합니다.
     *
     * @param traceId traceId
     * @param source 응답 source
     * @param result 응답 결과
     */
    @Override
    public void record(final String traceId, final String source, final UiTaskResult result) {
        Objects.requireNonNull(traceId, "traceId is null");
        Objects.requireNonNull(source, "source is null");
        Objects.requireNonNull(result, "result is null");

        final String key = buildKey(traceId);
        final String field = resolveField(source);
        if (field == null) {
            log.warn("DualResponse Redis 기록 무시 - 알 수 없는 source. traceId={}, source={}", traceId, source);
            return;
        }

        try {
            final HashOperations<String, Object, Object> hashOps = businessRedisTemplate.opsForHash();
            hashOps.put(key, field, result);

            final Object gatewayRaw = hashOps.get(key, FIELD_GATEWAY);
            final Object businessRaw = hashOps.get(key, FIELD_BUSINESS);

            if (gatewayRaw != null && businessRaw != null) {
                // 동시성 환경에서 중복 publish가 발생해도 Future.complete()는 1회만 성공합니다.
                businessRedisTemplate.convertAndSend(DUAL_COMPLETE_CHANNEL, traceId);
                log.debug("DualResponse 완료 신호 발행. traceId={}, channel={}",
                        traceId, DUAL_COMPLETE_CHANNEL);
            }
        } catch (Exception e) {
            log.error("DualResponse Redis 기록 실패. traceId={}, source={}, key={}",
                    traceId, source, key, e);
            throw e;
        }
    }

    /**
     * traceId 상태를 Redis에서 삭제합니다.
     *
     * @param traceId traceId
     */
    @Override
    public void cancel(final String traceId) {
        Objects.requireNonNull(traceId, "traceId is null");

        final String key = buildKey(traceId);
        try {
            final Boolean deleted = businessRedisTemplate.delete(key);
            if (log.isDebugEnabled()) {
                log.debug("DualResponse Redis 정리 완료. traceId={}, key={}, deleted={}", traceId, key, deleted);
            }
        } catch (Exception e) {
            log.warn("DualResponse Redis 정리 실패. traceId={}, key={}", traceId, key, e);
        }
    }

    /**
     * Redis Hash의 gateway/business 결과를 읽어 최종 판정 결과를 반환합니다.
     *
     * @param traceId traceId
     * @return 양쪽 결과가 모두 있으면 final result, 아니면 빈 Optional
     */
    @Override
    public Optional<UiDualTaskFinalResult> getResult(final String traceId) {
        Objects.requireNonNull(traceId, "traceId is null");

        final String key = buildKey(traceId);
        try {
            final HashOperations<String, Object, Object> hashOps = businessRedisTemplate.opsForHash();
            final Object gatewayRaw = hashOps.get(key, FIELD_GATEWAY);
            final Object businessRaw = hashOps.get(key, FIELD_BUSINESS);

            if (gatewayRaw == null || businessRaw == null) {
                return Optional.empty();
            }

            final UiTaskResult gatewayResult = toTaskResult(gatewayRaw);
            final UiTaskResult businessResult = toTaskResult(businessRaw);
            final boolean success = gatewayResult.isSuccess() && businessResult.isSuccess();

            return Optional.of(new UiDualTaskFinalResult(traceId, success, gatewayResult, businessResult));
        } catch (Exception e) {
            log.error("DualResponse Redis 조회 실패. traceId={}, key={}", traceId, key, e);
            return Optional.empty();
        }
    }

    /**
     * Redis에 저장된 객체를 UiTaskResult로 복원합니다.
     *
     * <p>JSON 직렬화 포맷/버전 차이로 인해 Map 형태로 읽히는 경우를 방어합니다.</p>
     *
     * @param raw Redis에서 읽은 원시 값
     * @return 복원된 UiTaskResult
     */
    private UiTaskResult toTaskResult(final Object raw) {
        if (raw instanceof UiTaskResult taskResult) {
            return taskResult;
        }

        if (raw instanceof Map<?, ?> map) {
            final String traceId = asString(map.get("traceId"));
            final String source = asString(map.get("source"));
            final UiTaskStatus status = UiTaskStatus.valueOf(asString(map.get("status")));
            final String errorCode = asNullableString(map.get("errorCode"));
            final String errorMsg = asNullableString(map.get("errorMsg"));

            if (status == UiTaskStatus.PASS) {
                return UiTaskResult.pass(traceId, source);
            }
            return UiTaskResult.fail(traceId, source, errorCode, errorMsg);
        }

        throw new IllegalStateException("지원하지 않는 DualResponse Redis 값 타입입니다. type=" + raw.getClass().getName());
    }

    /**
     * source 값을 Redis field 이름으로 변환합니다.
     *
     * @param source Kafka source 값
     * @return 대응 field 이름, 미지원 source면 null
     */
    private static String resolveField(final String source) {
        if (DualResponseRegistry.SOURCE_GATEWAY.equals(source)) {
            return FIELD_GATEWAY;
        }
        if (DualResponseRegistry.SOURCE_BUSINESS.equals(source)) {
            return FIELD_BUSINESS;
        }
        return null;
    }

    /**
     * Redis key를 생성합니다.
     *
     * @param traceId traceId
     * @return Redis key
     */
    private static String buildKey(final String traceId) {
        return KEY_PREFIX + traceId;
    }

    /**
     * null이 될 수 없는 문자열 변환 유틸입니다.
     *
     * @param value 원시 값
     * @return 문자열 값
     */
    private static String asString(final Object value) {
        if (value == null) {
            throw new IllegalStateException("필수 필드가 null입니다.");
        }
        return String.valueOf(value);
    }

    /**
     * nullable 문자열 변환 유틸입니다.
     *
     * @param value 원시 값
     * @return 문자열 또는 null
     */
    private static String asNullableString(final Object value) {
        if (value == null) {
            return null;
        }
        final String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }
}
