package com.nori.tc.ui.core.port.redis;

import java.util.Optional;

/**
 * Deploy/direct target 실행의 분산 lease 와 idempotency coordination 포트입니다(§13.4/§13.6).
 *
 * <p>lease 획득은 <b>fence epoch 발급과 같은 원자 연산</b>이어야 하고, 해제는 {@code owner + epoch}
 * 일치일 때만 성공해야 합니다. 비원자 {@code SETNX → GET → EXPIRE} 조합에는 소유권 교체를 나타내는
 * 단조 증가 값이 없어 stale owner 의 늦은 해제가 새 owner 의 lease 를 지웠습니다.</p>
 *
 * <p>실행 claim 은 <b>시작 전에 완료로 표시하지 않습니다</b>. 시작 전 {@code COMPLETED} 를 쓰면
 * 그 직후 죽은 실행이 "이미 끝난 것" 으로 보여 영원히 재개되지 않습니다.</p>
 */
public interface DeployExecutionCoordinationPort {

    /** {@link #claimTargetExecution} 결과입니다. */
    enum ExecutionClaim {
        /** 이 owner 가 실행을 claim 했습니다(최초 또는 같은 owner 재시도). */
        CLAIMED,
        /** 이미 완료 기록이 있어 재실행하지 않습니다. */
        ALREADY_COMPLETED,
        /** 다른 owner 가 실행 중입니다. */
        HELD_BY_OTHER
    }

    /**
     * eqpId 기준 실행 lease 획득을 시도합니다.
     *
     * @param eqpId lease 대상 설비 ID
     * @param ownerToken 현재 실행 owner 토큰
     * @param ttlMs lease TTL(ms)
     * @return 획득 성공 시 발급된 fence epoch, 실패 시 empty
     */
    Optional<Long> tryAcquireTargetLease(String eqpId, String ownerToken, long ttlMs);

    /**
     * owner + epoch 이 일치할 때만 eqpId lease 를 해제합니다.
     *
     * @param eqpId lease 대상 설비 ID
     * @param ownerToken 현재 실행 owner 토큰
     * @param epoch 보유 fence epoch
     */
    void releaseTargetLease(String eqpId, String ownerToken, long epoch);

    /**
     * 실행 claim 을 시도합니다.
     *
     * @param executionKey deploy/direct target 실행 idempotency key
     * @param ownerToken 현재 실행 owner 토큰
     * @param claimTtlMs claim 유지 TTL(ms) — 소유 프로세스가 죽으면 이 시간 뒤 재개 가능해야 합니다
     * @return claim 결과
     */
    ExecutionClaim claimTargetExecution(String executionKey, String ownerToken, long claimTtlMs);

    /**
     * 실행 완료를 기록합니다(재전달 idempotency 근거).
     *
     * @param executionKey 실행 idempotency key
     * @param retentionTtlMs 완료 기록 보존 TTL(ms)
     */
    void completeTargetExecution(String executionKey, long retentionTtlMs);

    /**
     * 테스트/초기 부팅용 noop 구현체를 반환합니다.
     *
     * @return 아무 것도 막지 않는 coordination 구현
     */
    static DeployExecutionCoordinationPort noop() {
        return new DeployExecutionCoordinationPort() {
            @Override
            public Optional<Long> tryAcquireTargetLease(
                    final String eqpId, final String ownerToken, final long ttlMs) {
                return Optional.of(0L);
            }

            @Override
            public void releaseTargetLease(final String eqpId, final String ownerToken, final long epoch) {
                // noop
            }

            @Override
            public ExecutionClaim claimTargetExecution(
                    final String executionKey, final String ownerToken, final long claimTtlMs) {
                return ExecutionClaim.CLAIMED;
            }

            @Override
            public void completeTargetExecution(final String executionKey, final long retentionTtlMs) {
                // noop
            }
        };
    }
}
