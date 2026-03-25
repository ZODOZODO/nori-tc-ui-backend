package com.nori.tc.ui.adapters.redis.coordination;

import com.nori.tc.ui.core.port.redis.DeployExecutionCoordinationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;

/**
 * Deploy target 분산 lease / idempotency를 Redis에 저장하는 어댑터입니다.
 */
@Service
@Primary
public class DeployExecutionRedisAdapter implements DeployExecutionCoordinationPort {

    private static final Logger log = LoggerFactory.getLogger(DeployExecutionRedisAdapter.class);

    private static final String LEASE_KEY_PREFIX = "tc:ui:backend:deploy:lease:";
    private static final String EXECUTION_KEY_PREFIX = "tc:ui:backend:deploy:execution:";

    private final RedisTemplate<String, Object> businessRedisTemplate;

    public DeployExecutionRedisAdapter(
            @Qualifier("businessRedisTemplate") final RedisTemplate<String, Object> businessRedisTemplate
    ) {
        this.businessRedisTemplate = Objects.requireNonNull(businessRedisTemplate,
                "businessRedisTemplate is null");
    }

    @Override
    public boolean tryAcquireTargetLease(final String eqpId, final String ownerToken, final long ttlMs) {
        Objects.requireNonNull(eqpId, "eqpId is null");
        Objects.requireNonNull(ownerToken, "ownerToken is null");

        final String key = LEASE_KEY_PREFIX + eqpId;
        final Duration ttl = Duration.ofMillis(Math.max(1L, ttlMs));
        try {
            final Boolean acquired = businessRedisTemplate.opsForValue().setIfAbsent(key, ownerToken, ttl);
            if (Boolean.TRUE.equals(acquired)) {
                return true;
            }

            final Object currentOwner = businessRedisTemplate.opsForValue().get(key);
            if (ownerToken.equals(asString(currentOwner))) {
                businessRedisTemplate.expire(key, ttl);
                return true;
            }
            return false;
        } catch (Exception redisException) {
            log.error("deploy lease 획득 실패. eqpId={}, ownerToken={}", eqpId, ownerToken, redisException);
            throw redisException;
        }
    }

    @Override
    public void releaseTargetLease(final String eqpId, final String ownerToken) {
        if (eqpId == null || ownerToken == null) {
            return;
        }

        final String key = LEASE_KEY_PREFIX + eqpId;
        try {
            final Object currentOwner = businessRedisTemplate.opsForValue().get(key);
            if (ownerToken.equals(asString(currentOwner))) {
                businessRedisTemplate.delete(key);
            }
        } catch (Exception redisException) {
            log.warn("deploy lease 해제 실패. eqpId={}, ownerToken={}", eqpId, ownerToken, redisException);
        }
    }

    @Override
    public boolean registerTargetExecution(final String executionKey, final long ttlMs) {
        Objects.requireNonNull(executionKey, "executionKey is null");

        final String key = EXECUTION_KEY_PREFIX + executionKey;
        try {
            return Boolean.TRUE.equals(businessRedisTemplate.opsForValue().setIfAbsent(
                    key,
                    "COMPLETED",
                    Duration.ofMillis(Math.max(1L, ttlMs))
            ));
        } catch (Exception redisException) {
            log.error("deploy execution idempotency 기록 실패. executionKey={}", executionKey, redisException);
            throw redisException;
        }
    }

    private static String asString(final Object raw) {
        if (raw == null) {
            return null;
        }
        return raw.toString();
    }
}
