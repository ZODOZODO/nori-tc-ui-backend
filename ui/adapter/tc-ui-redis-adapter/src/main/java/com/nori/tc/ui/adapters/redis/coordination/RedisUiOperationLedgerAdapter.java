package com.nori.tc.ui.adapters.redis.coordination;

import com.nori.tc.common.coordination.CoordinationKeys;
import com.nori.common.delivery.store.CoordinationOutcome;
import com.nori.tc.common.coordination.store.OperationLedgerRedisStore;
import com.nori.tc.common.coordination.types.OperationPhase;
import com.nori.tc.common.coordination.types.OperationRecord;
import com.nori.tc.common.coordination.types.OperationState;
import com.nori.tc.ui.core.coordination.model.UiOperationPhase;
import com.nori.tc.ui.core.coordination.model.UiOperationState;
import com.nori.tc.ui.core.coordination.model.UiOperationView;
import com.nori.tc.ui.core.coordination.model.UiParticipantProofView;
import com.nori.tc.ui.core.coordination.model.UiPhaseTransitionView;
import com.nori.tc.ui.core.coordination.port.UiOperationLedgerPort;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.nori.tc.ui.adapters.redis.coordination.UiCoordinationFailures.translating;

/**
 * {@link UiOperationLedgerPort} 의 Redis 구현입니다(§13.1).
 *
 * <p>결과 코드는 문자열 그대로 넘깁니다 — 어댑터가 성공/실패로 축약하면 executor 가 "아직 그 단계가
 * 아님"(PHASE_MISMATCH)과 "영구 거부"(STALE_FENCED)를 구분하지 못합니다.</p>
 */
public final class RedisUiOperationLedgerAdapter implements UiOperationLedgerPort {

    private final OperationLedgerRedisStore store;

    /**
     * 어댑터를 만듭니다.
     *
     * @param store 공통 operation ledger store
     */
    public RedisUiOperationLedgerAdapter(final OperationLedgerRedisStore store) {
        this.store = Objects.requireNonNull(store, "store is null");
    }

    @Override
    public CreateResult createOperation(
            final String operationId,
            final String manifestSha256,
            final String manifestJson,
            final Map<String, String> extraFields,
            final long nowEpochMs
    ) {
        final CoordinationOutcome outcome = translating(() ->
                store.createOperation(operationId, manifestSha256, manifestJson, extraFields, nowEpochMs));
        if (!CreateResult.EXISTING.equals(outcome.code())) {
            return new CreateResult(outcome.code(), UiOperationState.PENDING, UiOperationPhase.ACCEPTED, null, null);
        }
        // EXISTING(state, phase, errorCode, errorMsg, repairReason, desiredRevision)
        return new CreateResult(
                CreateResult.EXISTING,
                toState(outcome.value(0)),
                toPhase(outcome.value(1)),
                emptyToNull(outcome.value(2)),
                emptyToNull(outcome.value(3)));
    }

    @Override
    public String transition(
            final String operationId,
            final Set<UiOperationPhase> expectedPhases,
            final UiOperationPhase newPhase,
            final UiOperationState newState,
            final String executorSessionId,
            final Long executorEpoch,
            final String errorCode,
            final String errorMsg,
            final String repairReason,
            final long nowEpochMs
    ) {
        final Set<OperationPhase> expected = expectedPhases.stream()
                .map(phase -> OperationPhase.valueOf(phase.name()))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(OperationPhase.class)));
        return translating(() -> store.transition(
                operationId,
                expected,
                OperationPhase.valueOf(newPhase.name()),
                newState == null ? null : OperationState.valueOf(newState.name()),
                executorSessionId,
                executorEpoch,
                errorCode,
                errorMsg,
                repairReason,
                nowEpochMs).code());
    }

    @Override
    public String freezeParticipants(
            final String operationId,
            final List<String> participantIds,
            final boolean force,
            final long nowEpochMs
    ) {
        return translating(() -> store.freezeParticipants(operationId, participantIds, force, nowEpochMs).code());
    }

    @Override
    public String excludeExpiredParticipant(
            final String operationId,
            final String serviceName,
            final String expiredSessionId,
            final long nowEpochMs
    ) {
        return translating(() ->
                store.excludeExpiredParticipant(operationId, serviceName, expiredSessionId, nowEpochMs).code());
    }

    @Override
    public String closeBarrier(final String operationId, final List<String> resourceHeadKeys, final long nowEpochMs) {
        return translating(() -> store.closeBarrier(operationId, resourceHeadKeys, nowEpochMs).code());
    }

    @Override
    public String commitHead(
            final String operationId,
            final List<String> resourceHeadKeys,
            final List<Long> expectedBaseRevisions,
            final long nowEpochMs
    ) {
        return translating(() ->
                store.commitHead(operationId, resourceHeadKeys, expectedBaseRevisions, nowEpochMs).code());
    }

    @Override
    public int checkApplied(final String operationId, final boolean advanceToFinalizing, final long nowEpochMs) {
        final CoordinationOutcome outcome =
                translating(() -> store.checkApplied(operationId, advanceToFinalizing, nowEpochMs));
        if (!CoordinationOutcome.CODE_OK.equals(outcome.code())) {
            return -1;
        }
        try {
            return Integer.parseInt(outcome.value(0));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    @Override
    public String recordDbFinalized(
            final String operationId,
            final String executorSessionId,
            final long executorEpoch,
            final long finalizedRevision,
            final long nowEpochMs
    ) {
        return translating(() -> store
                .recordDbFinalized(operationId, executorSessionId, executorEpoch, finalizedRevision, nowEpochMs)
                .code());
    }

    @Override
    public String openBarrierAndPass(
            final String operationId,
            final List<String> resourceHeadKeys,
            final long nowEpochMs
    ) {
        return translating(() -> store.openBarrierAndPass(operationId, resourceHeadKeys, nowEpochMs).code());
    }

    @Override
    public String abort(
            final String operationId,
            final List<String> resourceHeadKeys,
            final String errorCode,
            final String errorMsg,
            final long nowEpochMs
    ) {
        final List<String> descriptorKeyPrefixes = resourceHeadKeys.stream()
                .map(CoordinationKeys::resourceRevisionKeyPrefixOf)
                .toList();
        return translating(() -> store.abort(
                operationId, resourceHeadKeys, descriptorKeyPrefixes, errorCode, errorMsg, nowEpochMs).code());
    }

    @Override
    public String markRolledBack(
            final String operationId,
            final String compensationOperationId,
            final long nowEpochMs
    ) {
        return translating(() ->
                store.markRolledBack(operationId, compensationOperationId, nowEpochMs).code());
    }

    @Override
    public String ackProof(
            final String operationId,
            final String participantId,
            final boolean applied,
            final long revision,
            final String digest,
            final String ownerShardId,
            final Long ownerEpoch,
            final String proofJson,
            final long nowEpochMs
    ) {
        /*
         * ownerEpoch 를 넘기면 스크립트가 이 lease hash 의 sessionId+epoch 와 대조한다(§13.5 stale epoch
         * reply 거부). shard id 없이 ownerEpoch 만 넘기면 store 가 lease 자리에 operation key 를 채우고
         * 그 hash 에는 sessionId 필드가 없으므로 모든 proof 가 STALE_FENCED 로 거부된다.
         */
        final String ownerLeaseKey = ownerShardId == null
                ? null
                : CoordinationKeys.gatewayShardLeaseKey(ownerShardId);
        final CoordinationOutcome outcome = translating(() -> applied
                ? store.ackApplied(operationId, participantId, revision, digest, ownerLeaseKey, ownerEpoch,
                        proofJson, nowEpochMs)
                : store.ackPrepared(operationId, participantId, revision, digest, ownerLeaseKey, ownerEpoch,
                        proofJson, nowEpochMs));
        return outcome.code();
    }

    @Override
    public Optional<UiOperationView> readOperation(final String operationId) {
        return translating(() -> store.readOperation(operationId).map(RedisUiOperationLedgerAdapter::toView));
    }

    @Override
    public List<UiParticipantProofView> readParticipantProofs(final String operationId) {
        return translating(() -> store.readParticipantProofs(operationId).stream()
                .map(proof -> new UiParticipantProofView(
                        proof.participantId(),
                        proof.preparedRevision(),
                        proof.preparedDigest(),
                        proof.appliedRevision(),
                        proof.appliedDigest(),
                        proof.ownerEpoch()))
                .toList());
    }

    @Override
    public List<UiPhaseTransitionView> readPhaseHistory(final String operationId) {
        return translating(() -> store.readPhaseHistory(operationId).stream()
                .map(transition -> new UiPhaseTransitionView(transition.phase(), transition.atEpochMs()))
                .toList());
    }

    @Override
    public Optional<String> readManifest(final String operationId) {
        return translating(() -> store.readManifest(operationId));
    }

    @Override
    public List<String> pendingOperationIds(final long limit) {
        return translating(() -> store.pendingOperationIds(Long.MAX_VALUE, limit));
    }

    private static UiOperationView toView(final OperationRecord record) {
        return new UiOperationView(
                record.operationId(),
                record.executionId(),
                record.eqpId(),
                record.eventType(),
                toState(record.state() == null ? null : record.state().name()),
                toPhase(record.phase() == null ? null : record.phase().name()),
                record.resourceKeys(),
                record.participants(),
                record.manifestSha256(),
                record.expectedBaseRevision(),
                record.desiredRevision(),
                record.dbFinalized(),
                record.compensatesRevision(),
                record.compensatesOperationId(),
                record.errorCode(),
                record.errorMsg(),
                record.repairReason(),
                record.createdAtEpochMs(),
                record.updatedAtEpochMs(),
                record.terminalAtEpochMs());
    }

    private static UiOperationState toState(final String name) {
        if (name == null || name.isBlank()) {
            return UiOperationState.UNKNOWN;
        }
        try {
            return UiOperationState.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return UiOperationState.UNKNOWN;
        }
    }

    private static UiOperationPhase toPhase(final String name) {
        if (name == null || name.isBlank()) {
            return UiOperationPhase.UNKNOWN;
        }
        try {
            return UiOperationPhase.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return UiOperationPhase.UNKNOWN;
        }
    }

    private static String emptyToNull(final String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
