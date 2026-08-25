package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.port.redis.DeployExecutionCoordinationPort;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 미사용 환경과 단위 테스트를 위한 인메모리 deploy coordination 구현입니다.
 *
 * <p>여기의 epoch 는 <b>프로세스 로컬 카운터</b>이며 클러스터 fence 가 아닙니다 — 단일 인스턴스
 * 구성에서만 유효합니다.</p>
 */
final class InMemoryDeployExecutionCoordinationPort implements DeployExecutionCoordinationPort {

    private final ConcurrentHashMap<String, LeaseHolder> leaseOwners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> executionOwners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> completedExecutions = new ConcurrentHashMap<>();
    private final AtomicLong epochCounter = new AtomicLong();

    private record LeaseHolder(String ownerToken, long epoch) {
    }

    @Override
    public Optional<Long> tryAcquireTargetLease(final String eqpId, final String ownerToken, final long ttlMs) {
        Objects.requireNonNull(eqpId, "eqpId is null");
        Objects.requireNonNull(ownerToken, "ownerToken is null");
        final LeaseHolder holder = new LeaseHolder(ownerToken, epochCounter.incrementAndGet());
        final LeaseHolder current = leaseOwners.putIfAbsent(eqpId, holder);
        if (current == null) {
            return Optional.of(holder.epoch());
        }
        return current.ownerToken().equals(ownerToken) ? Optional.of(current.epoch()) : Optional.empty();
    }

    @Override
    public void releaseTargetLease(final String eqpId, final String ownerToken, final long epoch) {
        if (eqpId == null || ownerToken == null) {
            return;
        }
        leaseOwners.remove(eqpId, new LeaseHolder(ownerToken, epoch));
    }

    @Override
    public ExecutionClaim claimTargetExecution(
            final String executionKey,
            final String ownerToken,
            final long claimTtlMs
    ) {
        Objects.requireNonNull(executionKey, "executionKey is null");
        Objects.requireNonNull(ownerToken, "ownerToken is null");
        if (completedExecutions.containsKey(executionKey)) {
            return ExecutionClaim.ALREADY_COMPLETED;
        }
        final String current = executionOwners.putIfAbsent(executionKey, ownerToken);
        if (current == null || current.equals(ownerToken)) {
            return ExecutionClaim.CLAIMED;
        }
        return ExecutionClaim.HELD_BY_OTHER;
    }

    @Override
    public void completeTargetExecution(final String executionKey, final long retentionTtlMs) {
        if (executionKey == null) {
            return;
        }
        completedExecutions.put(executionKey, Boolean.TRUE);
        executionOwners.remove(executionKey);
    }
}
