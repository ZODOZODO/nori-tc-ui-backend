package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiExecutorLease;
import com.nori.tc.ui.core.coordination.model.UiMembershipState;
import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
import com.nori.tc.ui.core.coordination.model.UiOperationPhase;
import com.nori.tc.ui.core.coordination.model.UiOperationState;
import com.nori.tc.ui.core.coordination.model.UiOperationView;
import com.nori.tc.ui.core.coordination.model.UiResourceHeadView;
import com.nori.tc.ui.core.coordination.port.UiCoordinationMembershipPort;
import com.nori.tc.ui.core.coordination.port.UiExecutorLeasePort;
import com.nori.tc.ui.core.coordination.port.UiFinalizedRevisionProvider;
import com.nori.tc.ui.core.coordination.port.UiOperationCommandPublisher;
import com.nori.tc.ui.core.coordination.port.UiOperationDbFinalizationPort;
import com.nori.tc.ui.core.coordination.port.UiOperationLedgerPort;
import com.nori.tc.ui.core.coordination.port.UiResourceHeadPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * executor 상태 기계의 <b>거부 조건</b>을 Docker 없이 고정합니다.
 *
 * <p>Redis 원장이 있어야만 검증되는 원자성은 IT 가 담당하고, 여기서는 "어떤 상태에서 executor 가
 * 아무것도 하지 않아야 하는가" 를 봅니다 — 이 조건이 무너지면 소유권 없는 Pod 나 fence 된 session 이
 * 결과를 만듭니다.</p>
 */
class UiOperationExecutorTest {

    private static final String OPERATION_ID = "op-1";
    private static final String MANIFEST_JSON = """
            {"schemaVersion":2,"eventType":"EQP_UPDATE","eqpId":"EQP001","requiresGatewayParticipant":false,
             "resourceKeys":["equipment:EQP001"],
             "descriptors":{"equipment:EQP001":{"schemaVersion":2,"eqpId":"EQP001"}},
             "expectedBaseRevisions":{"equipment:EQP001":0}}""";

    @Test
    @DisplayName("FENCED session 은 lease 조차 시도하지 않는다")
    void fencedSessionDoesNotAdvance() {
        final RecordingLeasePort leasePort = new RecordingLeasePort(true);
        final UiCoordinationSession session = session();
        session.fence();

        assertEquals(0, newExecutor(session, new RecordingLedgerPort(UiOperationPhase.ACCEPTED), leasePort)
                .advance(OPERATION_ID, 1_000L));
        assertEquals(0, leasePort.acquireAttempts.get(), "fence 된 session 이 lease 를 잡으면 안 됩니다.");
    }

    @Test
    @DisplayName("다른 Pod 가 lease 를 쥐고 있으면 전이하지 않는다")
    void leaseHeldByAnotherPodBlocksTransition() {
        final RecordingLedgerPort ledgerPort = new RecordingLedgerPort(UiOperationPhase.ACCEPTED);

        assertEquals(0, newExecutor(session(), ledgerPort, new RecordingLeasePort(false))
                .advance(OPERATION_ID, 1_000L));
        assertTrue(ledgerPort.transitions.isEmpty(), "lease 없이 phase 를 바꾸면 두 Pod 가 같은 operation 을 민다.");
    }

    @Test
    @DisplayName("terminal operation 은 더 전진시키지 않고 lease 를 반납한다")
    void terminalOperationIsNotAdvanced() {
        final RecordingLedgerPort ledgerPort = new RecordingLedgerPort(UiOperationPhase.BARRIER_OPEN);
        ledgerPort.state = UiOperationState.PASS;
        final RecordingLeasePort leasePort = new RecordingLeasePort(true);

        assertEquals(0, newExecutor(session(), ledgerPort, leasePort).advance(OPERATION_ID, 1_000L));
        assertTrue(ledgerPort.transitions.isEmpty());
        assertEquals(1, leasePort.releases.get(), "전진하지 않아도 lease 는 반드시 반납해야 합니다.");
    }

    @Test
    @DisplayName("participant 후보가 없으면 participant 집합을 고정하지 않는다")
    void noParticipantMeansNoFreeze() {
        final RecordingLedgerPort ledgerPort = new RecordingLedgerPort(UiOperationPhase.ACCEPTED);
        final UiOperationExecutor executor = new UiOperationExecutor(
                session(), properties(), ledgerPort, resourceHeadPort(), new RecordingLeasePort(true),
                emptyMembership(), new RecordingPublisher(), finalizer(), UiFinalizedRevisionProvider.absent(),
                new UiOperationManifestReader(new ObjectMapper()),
                new UiCoordinationMetrics(new SimpleMeterRegistry()));

        assertEquals(0, executor.advance(OPERATION_ID, 1_000L));
        assertTrue(ledgerPort.frozen.isEmpty(),
                "빈 집합으로 고정하면 아무도 적용하지 않은 operation 이 곧바로 PASS 조건을 만족한다.");
    }

    @Test
    @DisplayName("커밋 후 마지막 participant 가 만료되면 무한 재시도가 아니라 REPAIR_REQUIRED 로 확정한다")
    void lastParticipantExpiredBecomesRepairRequired() {
        final RecordingLedgerPort ledgerPort = new RecordingLedgerPort(UiOperationPhase.APPLYING);
        // 공통 원장은 제외하면 적용을 증명할 session 이 남지 않을 때 LAST_PARTICIPANT 를 돌려준다.
        ledgerPort.excludeCode = "LAST_PARTICIPANT";
        final UiOperationExecutor executor = new UiOperationExecutor(
                session(), properties(), ledgerPort, resourceHeadPort(), new RecordingLeasePort(true),
                emptyMembership(), new RecordingPublisher(), finalizer(), UiFinalizedRevisionProvider.absent(),
                new UiOperationManifestReader(new ObjectMapper()),
                new UiCoordinationMetrics(new SimpleMeterRegistry()));

        executor.advance(OPERATION_ID, 1_000L);

        assertEquals(List.of("LAST_PARTICIPANT_EXPIRED"), ledgerPort.repairReasons,
                "확정하지 않으면 operation 이 terminal 에 못 가고 그 설비 barrier 가 영구히 닫힌 채 남는다.");
    }

    @Test
    @DisplayName("commit 이후에는 abort 로 pointer 를 되돌리지 않는다")
    void abortAfterCommitIsRejected() {
        final RecordingLedgerPort ledgerPort = new RecordingLedgerPort(UiOperationPhase.COMMITTED);
        final UiOperationExecutor executor = newExecutor(session(), ledgerPort, new RecordingLeasePort(true));

        assertFalse(executor.abortBeforeCommit(OPERATION_ID, "X", "y", 1_000L));
        assertTrue(ledgerPort.aborts.isEmpty(), "commit 후 abort 는 §7.2 가 금지합니다.");
    }

    @Test
    @DisplayName("commit 전 실패는 abort 로 확정된다")
    void abortBeforeCommitIsApplied() {
        final RecordingLedgerPort ledgerPort = new RecordingLedgerPort(UiOperationPhase.PREPARING);
        final UiOperationExecutor executor = newExecutor(session(), ledgerPort, new RecordingLeasePort(true));

        assertTrue(executor.abortBeforeCommit(OPERATION_ID, "PLC_SPEC_INVALID", "변환 실패", 1_000L));
        assertEquals(List.of("PLC_SPEC_INVALID"), ledgerPort.aborts);
    }

    private UiOperationExecutor newExecutor(
            final UiCoordinationSession session,
            final RecordingLedgerPort ledgerPort,
            final UiExecutorLeasePort leasePort
    ) {
        return new UiOperationExecutor(
                session, properties(), ledgerPort, resourceHeadPort(), leasePort, membership(),
                new RecordingPublisher(), finalizer(), UiFinalizedRevisionProvider.absent(),
                new UiOperationManifestReader(new ObjectMapper()),
                new UiCoordinationMetrics(new SimpleMeterRegistry()));
    }

    private static UiCoordinationSession session() {
        final UiCoordinationSession session = new UiCoordinationSession(
                "tc-ui-backend", UUID.randomUUID().toString(), UUID.randomUUID().toString(), "pod");
        session.transitionTo(UiMembershipState.ACTIVE_SYNCED);
        return session;
    }

    private static UiCoordinationProperties properties() {
        final UiCoordinationProperties properties = new UiCoordinationProperties();
        properties.setBusinessServiceName("tc-business");
        return properties;
    }

    private static UiResourceHeadPort resourceHeadPort() {
        return new UiResourceHeadPort() {
            @Override
            public IssueResult issueCandidate(
                    final String operationId, final List<Candidate> candidates, final long nowEpochMs) {
                return new IssueResult(IssueCode.OK, 1L, null);
            }

            @Override
            public UiResourceHeadView readHead(final String resourceType, final String resourceId) {
                return new UiResourceHeadView(resourceType + ":" + resourceId, 0L, 0L, null, null);
            }

            @Override
            public Optional<String> readDescriptor(
                    final String resourceType, final String resourceId, final long revision) {
                return Optional.empty();
            }

            @Override
            public String headKeyOf(final String resourceKey) {
                return "head:" + resourceKey;
            }
        };
    }

    private static UiCoordinationMembershipPort membership() {
        return membership(List.of("tc-business:pod-a:boot-a"));
    }

    private static UiCoordinationMembershipPort emptyMembership() {
        return membership(List.of());
    }

    private static UiCoordinationMembershipPort membership(final List<String> sessions) {
        return new UiCoordinationMembershipPort() {
            @Override
            public Result register(final String sessionId, final String podName, final String protocolVersion,
                    final UiMembershipState initialState, final long heartbeatTtlMs, final long nowEpochMs) {
                return Result.OK;
            }

            @Override
            public Result renew(final String sessionId, final String protocolVersion,
                    final UiMembershipState state, final long lastAppliedGlobalRevision,
                    final long heartbeatTtlMs, final long nowEpochMs) {
                return Result.OK;
            }

            @Override
            public List<String> activeSyncedSessionIds(
                    final String serviceName, final String protocolVersion, final long limit) {
                return sessions;
            }

            @Override
            public boolean expired(final String serviceName, final String sessionId) {
                // 살아 있는 session 목록에 없으면 heartbeat 가 만료된 것이다.
                return !sessions.contains(sessionId);
            }
        };
    }

    private static UiOperationDbFinalizationPort finalizer() {
        return (operationId, manifest, desiredRevision) -> UiOperationDbFinalizationPort.Outcome.FINALIZED;
    }

    /** 발행만 기록하는 publisher 입니다. */
    private static final class RecordingPublisher implements UiOperationCommandPublisher {
        @Override
        public void publishPrepare(final String operationId, final UiOperationManifestView manifest,
                final long desiredRevision, final String manifestSha256, final String deadlineAt) {
            // 기록 불요 — 이 테스트는 거부 조건만 본다.
        }

        @Override
        public void publishApply(final String operationId, final UiOperationManifestView manifest,
                final long desiredRevision, final String manifestSha256, final String deadlineAt) {
            // 동일
        }
    }

    /** lease 획득 성공 여부를 고정하는 포트입니다. */
    private static final class RecordingLeasePort implements UiExecutorLeasePort {

        private final boolean acquirable;
        private final AtomicInteger acquireAttempts = new AtomicInteger();
        private final AtomicInteger releases = new AtomicInteger();

        private RecordingLeasePort(final boolean acquirable) {
            this.acquirable = acquirable;
        }

        @Override
        public Optional<UiExecutorLease> tryAcquire(
                final String operationId, final String sessionId, final long ttlMs, final long nowEpochMs) {
            acquireAttempts.incrementAndGet();
            return acquirable ? Optional.of(new UiExecutorLease(operationId, sessionId, 1L)) : Optional.empty();
        }

        @Override
        public boolean renew(final UiExecutorLease lease, final long ttlMs, final long nowEpochMs) {
            return true;
        }

        @Override
        public void release(final UiExecutorLease lease, final long nowEpochMs) {
            releases.incrementAndGet();
        }
    }

    /** 전이 시도를 기록하고 phase 를 고정하는 원장입니다. */
    private static final class RecordingLedgerPort implements UiOperationLedgerPort {

        private final UiOperationPhase phase;
        private UiOperationState state = UiOperationState.PENDING;
        private final List<String> transitions = new ArrayList<>();
        private final List<String> aborts = new ArrayList<>();
        private final List<List<String>> frozen = new ArrayList<>();
        private final List<String> repairReasons = new ArrayList<>();
        private String excludeCode = "NOT_EXPIRED";

        private RecordingLedgerPort(final UiOperationPhase phase) {
            this.phase = phase;
        }

        @Override
        public CreateResult createOperation(final String operationId, final String manifestSha256,
                final String manifestJson, final Map<String, String> extraFields, final long nowEpochMs) {
            return new CreateResult(CreateResult.CREATED, UiOperationState.PENDING,
                    UiOperationPhase.ACCEPTED, null, null);
        }

        @Override
        public String transition(final String operationId, final Set<UiOperationPhase> expectedPhases,
                final UiOperationPhase newPhase, final UiOperationState newState, final String executorSessionId,
                final Long executorEpoch, final String errorCode, final String errorMsg,
                final String repairReason, final long nowEpochMs) {
            transitions.add(newPhase.name());
            if (newState == UiOperationState.REPAIR_REQUIRED) {
                repairReasons.add(repairReason);
                state = UiOperationState.REPAIR_REQUIRED;
                return "OK";
            }
            return "PHASE_MISMATCH";
        }

        @Override
        public String freezeParticipants(final String operationId, final List<String> participantIds,
                final boolean force, final long nowEpochMs) {
            frozen.add(participantIds);
            return "OK";
        }

        @Override
        public String excludeExpiredParticipant(final String operationId, final String serviceName,
                final String expiredSessionId, final long nowEpochMs) {
            return excludeCode;
        }

        @Override
        public String closeBarrier(
                final String operationId, final List<String> resourceHeadKeys, final long nowEpochMs) {
            return "MISSING_PREPARE";
        }

        @Override
        public String commitHead(final String operationId, final List<String> resourceHeadKeys,
                final List<Long> expectedBaseRevisions, final long nowEpochMs) {
            return "PHASE_MISMATCH";
        }

        @Override
        public int checkApplied(
                final String operationId, final boolean advanceToFinalizing, final long nowEpochMs) {
            return 1;
        }

        @Override
        public String recordDbFinalized(final String operationId, final String executorSessionId,
                final long executorEpoch, final long finalizedRevision, final long nowEpochMs) {
            return "OK";
        }

        @Override
        public String openBarrierAndPass(
                final String operationId, final List<String> resourceHeadKeys, final long nowEpochMs) {
            return "OK";
        }

        @Override
        public String abort(final String operationId, final List<String> resourceHeadKeys,
                final String errorCode, final String errorMsg, final long nowEpochMs) {
            aborts.add(errorCode);
            return "OK";
        }

        @Override
        public String ackProof(final String operationId, final String participantId, final boolean applied,
                final long revision, final String digest, final String ownerLeaseKey, final Long ownerEpoch,
                final String proofJson, final long nowEpochMs) {
            return "OK";
        }

        @Override
        public Optional<UiOperationView> readOperation(final String operationId) {
            return Optional.of(new UiOperationView(
                    operationId, null, "EQP001", "EQP_UPDATE", state, phase,
                    List.of("equipment:EQP001"), List.of("tc-business:pod-a:boot-a"),
                    "a".repeat(64), 0L, 1L, false, null, null, null, null, 1L, 1L, null));
        }

        @Override
        public java.util.List<com.nori.tc.ui.core.coordination.model.UiParticipantProofView> readParticipantProofs(final String operationId) {
            return java.util.List.of();
        }

        @Override

        public Optional<String> readManifest(final String operationId) {
            return Optional.of(MANIFEST_JSON);
        }

        @Override
        public List<String> pendingOperationIds(final long limit) {
            return List.of(OPERATION_ID);
        }
    }
}
