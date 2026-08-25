package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationPhase;
import com.nori.tc.ui.core.coordination.model.UiOperationState;
import com.nori.tc.ui.core.coordination.model.UiOperationStatusView;
import com.nori.tc.ui.core.coordination.model.UiOperationView;
import com.nori.tc.ui.core.coordination.port.UiOperationLedgerPort;
import com.nori.tc.ui.core.model.AsyncResultEntry;
import com.nori.tc.ui.core.model.AsyncStatus;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.port.redis.AsyncResultStorePort;
import com.nori.tc.ui.domain.task.UiTaskStatus;

import java.util.Objects;
import java.util.Optional;

/**
 * operation 조회를 단일 계약으로 제공합니다(§13.3 / Plan §8 B1).
 *
 * <p>{@code /api/async/{id}} 를 범용 operation 조회로 확장하므로 {@code id} 는 operationId 와 레거시
 * traceId 를 모두 받습니다. 조정 원장에 없으면 레거시 async 결과 저장소를 봅니다.</p>
 *
 * <p><b>TIMEOUT 은 terminal 이 아닙니다.</b> 관찰 deadline 이 지나도 operation 은 살아 있고, 늦게
 * 도착한 적용이 PASS 또는 보상 결과로 수렴합니다. 그래서 지연은 상태가 아니라 {@code RECONCILING}
 * phase 로만 표현합니다 — 여기서 terminal 을 만들면 그 수렴이 불가능해집니다.</p>
 */
public final class UiOperationQueryService {

    private final UiOperationLedgerPort ledgerPort;
    private final AsyncResultStorePort asyncResultStorePort;
    private final UiCoordinationProperties properties;

    /**
     * 조회 서비스를 만듭니다.
     *
     * @param ledgerPort operation ledger 포트
     * @param asyncResultStorePort 레거시 async 결과 저장소
     * @param properties 조정 설정
     */
    public UiOperationQueryService(
            final UiOperationLedgerPort ledgerPort,
            final AsyncResultStorePort asyncResultStorePort,
            final UiCoordinationProperties properties
    ) {
        this.ledgerPort = Objects.requireNonNull(ledgerPort, "ledgerPort is null");
        this.asyncResultStorePort = Objects.requireNonNull(asyncResultStorePort, "asyncResultStorePort is null");
        this.properties = Objects.requireNonNull(properties, "properties is null");
    }

    /**
     * operationId 또는 traceId 로 상태를 조회합니다.
     *
     * @param id operationId 또는 traceId
     * @param nowEpochMs 현재 시각
     * @return 상태 뷰(존재하지 않으면 empty)
     * @throws UiCoordinationUnavailableException 조정 원장 조회 불가(503 — 404 로 환원하지 않습니다)
     */
    public Optional<UiOperationStatusView> find(final String id, final long nowEpochMs) {
        Objects.requireNonNull(id, "id is null");

        final Optional<UiOperationView> operation = ledgerPort.readOperation(id);
        if (operation.isPresent()) {
            return Optional.of(toStatusView(operation.get(), nowEpochMs));
        }
        return asyncResultStorePort.getWithStatus(id).map(UiOperationQueryService::fromLegacyAsyncEntry);
    }

    private UiOperationStatusView toStatusView(final UiOperationView operation, final long nowEpochMs) {
        return new UiOperationStatusView(
                operation.operationId(),
                operation.eqpId(),
                operation.state(),
                displayPhase(operation, nowEpochMs),
                operation.desiredRevision(),
                operation.createdAtEpochMs(),
                operation.updatedAtEpochMs(),
                operation.terminalAtEpochMs(),
                operation.errorCode(),
                operation.errorMsg(),
                operation.repairReason());
    }

    /**
     * 관찰 deadline 을 넘긴 비terminal operation 을 {@code RECONCILING} 으로 표시합니다.
     *
     * <p>저장된 phase 는 바꾸지 않습니다 — 실제 진행 지점을 잃으면 인계받은 executor 가 어디부터
     * 이어야 할지 알 수 없습니다.</p>
     */
    private UiOperationPhase displayPhase(final UiOperationView operation, final long nowEpochMs) {
        if (operation.terminal()) {
            return operation.phase();
        }
        final Long updatedAt = operation.updatedAtEpochMs();
        if (updatedAt == null) {
            return operation.phase();
        }
        if (nowEpochMs - updatedAt > properties.getObservationDeadlineMs()) {
            return UiOperationPhase.RECONCILING;
        }
        return operation.phase();
    }

    /**
     * 레거시 async 결과를 operation 조회 계약으로 매핑합니다.
     *
     * <p>{@link AsyncStatus#TIMEOUT} 은 {@code PENDING} + {@code RECONCILING} 으로 매핑합니다 —
     * §13.3 이 408/TIMEOUT terminal 생성을 금지하고, 늦은 결과가 도착하면 그대로 확정돼야 합니다.</p>
     */
    private static UiOperationStatusView fromLegacyAsyncEntry(final AsyncResultEntry entry) {
        return switch (entry.status()) {
            case PENDING -> new UiOperationStatusView(
                    entry.traceId(), null, UiOperationState.PENDING, UiOperationPhase.ACCEPTED,
                    null, null, null, null, null, null, null);
            case TIMEOUT -> new UiOperationStatusView(
                    entry.traceId(), null, UiOperationState.PENDING, UiOperationPhase.RECONCILING,
                    null, null, null, null, null, null, null);
            case COMPLETED -> completedLegacyView(entry);
        };
    }

    private static UiOperationStatusView completedLegacyView(final AsyncResultEntry entry) {
        final UiCommandReply reply = entry.reply();
        final UiOperationState status =
                reply.status() == UiTaskStatus.PASS ? UiOperationState.PASS : UiOperationState.FAIL;
        return new UiOperationStatusView(
                entry.traceId(),
                reply.eqpId(),
                status,
                UiOperationPhase.BARRIER_OPEN,
                null,
                null,
                null,
                null,
                reply.errorCode(),
                reply.errorMsg(),
                null);
    }
}
