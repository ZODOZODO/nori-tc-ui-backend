package com.nori.tc.ui.adapters.redis.coordination;

import com.nori.tc.common.coordination.CoordinationKeys;
import com.nori.common.delivery.store.CoordinationOutcome;
import com.nori.tc.common.coordination.store.FencedLeaseRedisStore;
import com.nori.tc.ui.core.coordination.model.UiExecutorLease;
import com.nori.tc.ui.core.coordination.port.UiExecutorLeasePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

import static com.nori.tc.ui.adapters.redis.coordination.UiCoordinationFailures.translating;

/**
 * {@link UiExecutorLeasePort} 의 Redis 구현입니다(§13.4).
 *
 * <p>acquire 는 epoch counter 증가와 lease 설정을 <b>한 스크립트</b>에서 수행합니다. 종전의
 * {@code SETNX → GET → EXPIRE} 조합은 소유권 교체를 나타내는 단조 증가 값이 없어, 늦게 깨어난 옛
 * executor 의 전이를 거부할 수단 자체가 없었습니다.</p>
 */
public final class RedisUiExecutorLeaseAdapter implements UiExecutorLeasePort {

    private static final Logger log = LoggerFactory.getLogger(RedisUiExecutorLeaseAdapter.class);

    private final FencedLeaseRedisStore store;

    /**
     * 어댑터를 만듭니다.
     *
     * @param store 공통 fenced lease store
     */
    public RedisUiExecutorLeaseAdapter(final FencedLeaseRedisStore store) {
        this.store = Objects.requireNonNull(store, "store is null");
    }

    @Override
    public Optional<UiExecutorLease> tryAcquire(
            final String operationId,
            final String sessionId,
            final long ttlMs,
            final long nowEpochMs
    ) {
        final CoordinationOutcome outcome = translating(() -> store.acquire(
                CoordinationKeys.executorEpochKey(operationId),
                CoordinationKeys.executorLeaseKey(operationId),
                sessionId,
                ttlMs,
                "{\"role\":\"operation-executor\"}",
                nowEpochMs));
        if (!"OK".equals(outcome.code())) {
            if (log.isDebugEnabled()) {
                log.debug("UI_EXECUTOR_LEASE_HELD. operationId={}, code={}, owner={}",
                        operationId, outcome.code(), outcome.value(0));
            }
            return Optional.empty();
        }
        return Optional.of(new UiExecutorLease(operationId, sessionId, Long.parseLong(outcome.value(0))));
    }

    @Override
    public boolean renew(final UiExecutorLease lease, final long ttlMs, final long nowEpochMs) {
        return "OK".equals(translating(() -> store.renew(
                CoordinationKeys.executorLeaseKey(lease.operationId()),
                lease.sessionId(),
                lease.epoch(),
                ttlMs,
                nowEpochMs).code()));
    }

    @Override
    public void release(final UiExecutorLease lease, final long nowEpochMs) {
        final String code = translating(() -> store.release(
                CoordinationKeys.executorLeaseKey(lease.operationId()),
                lease.sessionId(),
                lease.epoch(),
                nowEpochMs).code());
        if (!"OK".equals(code) && log.isDebugEnabled()) {
            log.debug("UI_EXECUTOR_LEASE_RELEASE_SKIPPED. operationId={}, code={}", lease.operationId(), code);
        }
    }
}
