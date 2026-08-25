package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.port.UiOperationLedgerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 미종결 operation 을 발견해 executor 로 전진시키는 in-process reconciler 입니다(§13.4).
 *
 * <p>모든 Pod 가 미종결 operation 을 <b>검색</b>할 수 있지만 전이는 lease 를 얻은 한 session 만
 * 수행합니다. 그래서 기동 직후 이 reconciler 를 한 번 돌리면, 접수 Pod 가 죽어 남겨진 operation 을
 * 다른 Pod 가 인계받습니다.</p>
 *
 * <p>한 operation 의 실패가 나머지를 막지 않도록 개별 예외를 격리합니다 — 여기서 예외를 그대로
 * 올리면 backlog 앞쪽의 손상된 operation 하나가 전체 복구를 정지시킵니다.</p>
 */
public final class UiOperationReconciler {

    private static final Logger log = LoggerFactory.getLogger(UiOperationReconciler.class);

    private final UiOperationLedgerPort ledgerPort;
    private final UiOperationExecutor executor;
    private final UiCoordinationProperties properties;
    private final UiCoordinationMetrics metrics;

    /**
     * reconciler 를 만듭니다.
     *
     * @param ledgerPort operation ledger 포트
     * @param executor operation executor
     * @param properties 조정 설정
     * @param metrics 조정 metric
     */
    public UiOperationReconciler(
            final UiOperationLedgerPort ledgerPort,
            final UiOperationExecutor executor,
            final UiCoordinationProperties properties,
            final UiCoordinationMetrics metrics
    ) {
        this.ledgerPort = Objects.requireNonNull(ledgerPort, "ledgerPort is null");
        this.executor = Objects.requireNonNull(executor, "executor is null");
        this.properties = Objects.requireNonNull(properties, "properties is null");
        this.metrics = Objects.requireNonNull(metrics, "metrics is null");
    }

    /**
     * 미종결 operation 전체를 한 바퀴 전진시킵니다.
     *
     * @return 수행한 전이 수 합계
     */
    public int reconcileAll() {
        final List<String> pending = ledgerPort.pendingOperationIds(properties.getPendingScanLimit());
        metrics.recordRecoveryBacklog(pending.size());
        if (pending.isEmpty()) {
            return 0;
        }
        if (pending.size() > properties.getRecoveryBacklogLimit()) {
            log.error("UI_RECOVERY_BACKLOG_EXCEEDED. pending={}, limit={} — mutation readiness 를 내립니다.",
                    pending.size(), properties.getRecoveryBacklogLimit());
        }

        final long now = System.currentTimeMillis();
        int steps = 0;
        for (final String operationId : pending) {
            try {
                steps += executor.advance(operationId, now);
            } catch (RuntimeException ex) {
                log.error("UI_OPERATION_ADVANCE_FAILED. operationId={}", operationId, ex);
            }
        }
        return steps;
    }

    /**
     * 한 operation 만 즉시 전진시킵니다(접수 직후 지연 축소용).
     *
     * @param operationId 대상 operation
     * @return 수행한 전이 수
     */
    public int reconcileOne(final String operationId) {
        try {
            return executor.advance(operationId, System.currentTimeMillis());
        } catch (RuntimeException ex) {
            log.error("UI_OPERATION_ADVANCE_FAILED. operationId={}", operationId, ex);
            return 0;
        }
    }

    /**
     * 현재 복구 backlog 가 안전 한도를 넘었는지 반환합니다(§13.11).
     *
     * @return 한도 초과면 true
     */
    public boolean backlogExceeded() {
        return metrics.recoveryBacklog() > properties.getRecoveryBacklogLimit();
    }
}
