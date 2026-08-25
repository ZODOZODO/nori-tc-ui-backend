package com.nori.tc.ui.core.coordination.port;

import com.nori.tc.ui.core.coordination.model.UiExecutorLease;

import java.util.Optional;

/**
 * operation executor lease 포트입니다(§13.4).
 *
 * <p>acquire 는 epoch counter 증가와 lease 설정을 한 원자 연산에서 수행하고, renew/release 는
 * {@code session + epoch} 일치일 때만 성공한다 — 비원자 {@code GET → EXPIRE} / {@code GET → DELETE}
 * 는 stale executor 의 늦은 전이를 거부할 수단이 없다.</p>
 */
public interface UiExecutorLeasePort {

    /**
     * lease 획득을 시도합니다.
     *
     * @param operationId 대상 operation
     * @param sessionId 획득 시도 session
     * @param ttlMs lease TTL
     * @param nowEpochMs 현재 시각
     * @return 획득 성공 시 lease, 다른 session 이 보유 중이면 empty
     */
    Optional<UiExecutorLease> tryAcquire(String operationId, String sessionId, long ttlMs, long nowEpochMs);

    /**
     * lease 를 갱신합니다.
     *
     * @param lease 보유 lease
     * @param ttlMs 갱신 TTL
     * @param nowEpochMs 현재 시각
     * @return 갱신 성공 여부
     */
    boolean renew(UiExecutorLease lease, long ttlMs, long nowEpochMs);

    /**
     * lease 를 해제합니다.
     *
     * @param lease 보유 lease
     * @param nowEpochMs 현재 시각
     */
    void release(UiExecutorLease lease, long nowEpochMs);
}
