package com.nori.tc.ui.adapters.redis.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import com.nori.tc.ui.core.port.redis.TokenCachePort;
import com.nori.tc.ui.core.properties.UiAuthProperties;
import com.nori.tc.ui.domain.auth.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Business Redis(6380)를 이용한 인증 토큰 캐시 서비스입니다.
 *
 * <p>{@link TokenCachePort}를 구현하며, 매 요청마다 DB를 조회하지 않고
 * Redis 캐시에서 {@link UserPrincipal}을 빠르게 조회할 수 있도록 합니다.</p>
 *
 * <p>키 형식: {@code tc:ui:backend:session:{sha256(token)}}</p>
 * <p>TTL: {@code tc.ui.backend.auth.token-cache-ttl-seconds} 설정값 (기본 300초)</p>
 *
 * <p>{@link UserPrincipal}은 Redis 저장 시 {@link RedisUiSessionEntry}로 변환하여
 * JSON 직렬화 방식으로 저장합니다.</p>
 */
@Service
@EnableConfigurationProperties(UiAuthProperties.class)
public class UiSessionCacheService implements TokenCachePort {

    private static final Logger log = LoggerFactory.getLogger(UiSessionCacheService.class);

    /** Redis 토큰 캐시 키 접두사 */
    private static final String KEY_PREFIX = "tc:ui:backend:session:";

    private final RedisTemplate<String, Object> businessRedisTemplate;
    private final UiAuthProperties authProperties;
    private final ObjectMapper redisObjectMapper;
    private final Counter hitCounter;
    private final Counter missCounter;

    public UiSessionCacheService(
            @Qualifier("businessRedisTemplate") final RedisTemplate<String, Object> businessRedisTemplate,
            final UiAuthProperties authProperties,
            @Qualifier("uiRedisObjectMapper") final ObjectMapper redisObjectMapper,
            final MeterRegistry meterRegistry
    ) {
        this.businessRedisTemplate = Objects.requireNonNull(businessRedisTemplate,
                "businessRedisTemplate 은 null 이 될 수 없습니다.");
        this.authProperties = Objects.requireNonNull(authProperties,
                "authProperties 는 null 이 될 수 없습니다.");
        this.redisObjectMapper = Objects.requireNonNull(redisObjectMapper,
                "redisObjectMapper 는 null 이 될 수 없습니다.");
        this.hitCounter = meterRegistry == null ? null : meterRegistry.counter("session_cache.hit");
        this.missCounter = meterRegistry == null ? null : meterRegistry.counter("session_cache.miss");
    }

    /**
     * 캐시에서 토큰에 해당하는 {@link UserPrincipal}을 조회합니다.
     *
     * <p>캐시 HIT 시 DB 조회 없이 즉시 반환합니다.
     * 캐시 MISS(키 없음) 또는 역직렬화 오류 시 빈 Optional을 반환합니다.
     * 역직렬화 오류는 WARN 로그를 남기고 해당 캐시 항목을 자동 제거합니다.</p>
     *
     * @param token 조회할 세션 토큰
     * @return 캐시에 존재하면 UserPrincipal, 없거나 만료 시 빈 Optional
     */
    @Override
    public Optional<UserPrincipal> get(final String token) {
        Objects.requireNonNull(token, "token 은 null 이 될 수 없습니다.");

        final String key = buildCacheKey(token);
        try {
            final Object raw = businessRedisTemplate.opsForValue().get(key);
            if (raw == null) {
                incrementMissCounter();
                if (log.isTraceEnabled()) {
                    log.trace("토큰 캐시 MISS. key={}", key);
                }
                return Optional.empty();
            }

            if (raw instanceof RedisUiSessionEntry entry) {
                incrementHitCounter();
                if (log.isDebugEnabled()) {
                    log.debug("토큰 캐시 HIT. key={}, userPk={}", key, entry.getUserPk());
                }
                return Optional.of(entry.toPrincipal());
            }
            if (raw instanceof java.util.Map<?, ?> map) {
                // 직렬화 포맷 전환 과정에서 LinkedHashMap으로 역직렬화되는 경우를 방어합니다.
                final RedisUiSessionEntry entry = redisObjectMapper.convertValue(map, RedisUiSessionEntry.class);
                incrementHitCounter();
                if (log.isDebugEnabled()) {
                    log.debug("토큰 캐시 HIT(Map 변환). key={}, userPk={}", key, entry.getUserPk());
                }
                return Optional.of(entry.toPrincipal());
            }

            // 저장된 타입이 예상과 다를 경우 — 직렬화 형식 변경 등으로 발생 가능
            incrementMissCounter();
            log.warn("토큰 캐시 타입 불일치, 해당 항목 제거. key={}, actualType={}",
                    key, raw.getClass().getName());
            businessRedisTemplate.delete(key);
            return Optional.empty();

        } catch (Exception e) {
            // Redis 장애나 역직렬화 오류가 발생해도 인증 흐름이 멈추지 않도록 빈 Optional을 반환합니다.
            // 이후 ValidateTokenUseCase가 DB를 통해 재검증합니다.
            incrementMissCounter();
            log.warn("토큰 캐시 조회 실패. key={}", key, e);
            return Optional.empty();
        }
    }

    /**
     * 토큰과 {@link UserPrincipal}을 Redis 캐시에 저장합니다.
     *
     * <p>ValidateTokenUseCase에서 DB 조회 후 호출됩니다.
     * TTL은 {@code tc.ui.backend.auth.token-cache-ttl-seconds} 설정값을 따릅니다.</p>
     *
     * @param token     캐시 키로 사용할 세션 토큰
     * @param principal 캐싱할 사용자 인증 정보
     */
    @Override
    public void put(final String token, final UserPrincipal principal) {
        Objects.requireNonNull(token, "token 은 null 이 될 수 없습니다.");
        Objects.requireNonNull(principal, "principal 은 null 이 될 수 없습니다.");

        final String key = buildCacheKey(token);
        final Duration ttl = Duration.ofSeconds(authProperties.tokenCacheTtlSeconds());
        final RedisUiSessionEntry entry = RedisUiSessionEntry.from(principal);

        try {
            businessRedisTemplate.opsForValue().set(key, entry, ttl);
            if (log.isDebugEnabled()) {
                log.debug("토큰 캐시 저장. key={}, userPk={}, ttlSeconds={}",
                        key, principal.userPk(), ttl.getSeconds());
            }
        } catch (Exception e) {
            // 캐시 저장 실패는 치명적이지 않습니다. 다음 요청에서 DB를 통해 재검증됩니다.
            log.warn("토큰 캐시 저장 실패. key={}, userPk={}", key, principal.userPk(), e);
        }
    }

    /**
     * Redis 캐시에서 해당 토큰의 항목을 즉시 삭제합니다.
     *
     * <p>LogoutUseCase에서 로그아웃 시 캐시를 무효화하는 데 사용합니다.
     * 삭제 실패 시 WARN 로그를 남기지만 로그아웃 흐름을 중단하지 않습니다.</p>
     *
     * @param token 삭제할 세션 토큰
     */
    @Override
    public void evict(final String token) {
        Objects.requireNonNull(token, "token 은 null 이 될 수 없습니다.");

        final String key = buildCacheKey(token);
        try {
            final Boolean deleted = businessRedisTemplate.delete(key);
            if (log.isDebugEnabled()) {
                log.debug("토큰 캐시 제거. key={}, deleted={}", key, deleted);
            }
        } catch (Exception e) {
            log.warn("토큰 캐시 제거 실패. key={}", key, e);
        }
    }

    /**
     * 토큰 원문을 SHA-256으로 해시하여 Redis 키를 생성합니다.
     *
     * <p>원문 토큰이 Redis 키에 노출되면 Redis 접근 권한 유출 시 인증 토큰이 직접 노출될 수 있으므로,
     * 키 저장 경로에서는 원문 대신 고정 길이 해시를 사용합니다.</p>
     *
     * @param token 원본 세션 토큰
     * @return {@code tc:ui:backend:session:{sha256(token)}} 형식의 캐시 키
     */
    private static String buildCacheKey(final String token) {
        try {
            final MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = messageDigest.digest(token.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }

    /**
     * 세션 캐시 HIT 메트릭을 증가시킵니다.
     */
    private void incrementHitCounter() {
        if (hitCounter == null) {
            return;
        }
        hitCounter.increment();
    }

    /**
     * 세션 캐시 MISS 메트릭을 증가시킵니다.
     */
    private void incrementMissCounter() {
        if (missCounter == null) {
            return;
        }
        missCounter.increment();
    }
}
