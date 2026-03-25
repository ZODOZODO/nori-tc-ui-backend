package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.port.redis.DeployExecutionCoordinationPort;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 미사용 환경과 단위 테스트를 위한 인메모리 deploy coordination 구현입니다.
 */
final class InMemoryDeployExecutionCoordinationPort implements DeployExecutionCoordinationPort {

    private final ConcurrentHashMap<String, String> leaseOwners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> executionKeys = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquireTargetLease(final String eqpId, final String ownerToken, final long ttlMs) {
        Objects.requireNonNull(eqpId, "eqpId is null");
        Objects.requireNonNull(ownerToken, "ownerToken is null");
        final String currentOwner = leaseOwners.putIfAbsent(eqpId, ownerToken);
        return currentOwner == null || currentOwner.equals(ownerToken);
    }

    @Override
    public void releaseTargetLease(final String eqpId, final String ownerToken) {
        if (eqpId == null || ownerToken == null) {
            return;
        }
        leaseOwners.remove(eqpId, ownerToken);
    }

    @Override
    public boolean registerTargetExecution(final String executionKey, final long ttlMs) {
        Objects.requireNonNull(executionKey, "executionKey is null");
        return executionKeys.putIfAbsent(executionKey, Boolean.TRUE) == null;
    }
}
