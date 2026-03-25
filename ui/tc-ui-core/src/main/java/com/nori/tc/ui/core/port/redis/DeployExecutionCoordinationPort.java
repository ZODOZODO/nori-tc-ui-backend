package com.nori.tc.ui.core.port.redis;

/**
 * Deploy target 실행의 분산 lease / idempotency coordination 포트입니다.
 *
 * <p>목적:</p>
 * <ul>
 *   <li>동일 eqpId target이 다중 인스턴스에서 동시에 실행되지 않도록 lease를 제공합니다.</li>
 *   <li>동일 deploy target execution key의 중복 실행을 차단합니다.</li>
 * </ul>
 */
public interface DeployExecutionCoordinationPort {

    /**
     * eqpId 기준 실행 lease 획득을 시도합니다.
     *
     * @param eqpId lease 대상 설비 ID
     * @param ownerToken 현재 실행 owner 토큰
     * @param ttlMs lease TTL(ms)
     * @return 획득 성공 시 true
     */
    boolean tryAcquireTargetLease(String eqpId, String ownerToken, long ttlMs);

    /**
     * ownerToken이 일치할 때만 eqpId lease를 해제합니다.
     *
     * @param eqpId lease 대상 설비 ID
     * @param ownerToken 현재 실행 owner 토큰
     */
    void releaseTargetLease(String eqpId, String ownerToken);

    /**
     * 동일 executionKey가 이미 처리 중이거나 처리 완료되었는지 확인하고 최초 등록만 허용합니다.
     *
     * @param executionKey deploy target 실행 idempotency key
     * @param ttlMs idempotency 유지 TTL(ms)
     * @return 최초 등록이면 true
     */
    boolean registerTargetExecution(String executionKey, long ttlMs);

    /**
     * 테스트/초기 부팅용 noop 구현체를 반환합니다.
     *
     * @return 아무 것도 막지 않는 coordination 구현
     */
    static DeployExecutionCoordinationPort noop() {
        return new DeployExecutionCoordinationPort() {
            @Override
            public boolean tryAcquireTargetLease(final String eqpId, final String ownerToken, final long ttlMs) {
                return true;
            }

            @Override
            public void releaseTargetLease(final String eqpId, final String ownerToken) {
                // noop
            }

            @Override
            public boolean registerTargetExecution(final String executionKey, final long ttlMs) {
                return true;
            }
        };
    }
}
