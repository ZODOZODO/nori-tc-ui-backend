package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationPhase;
import com.nori.tc.ui.core.coordination.model.UiOperationState;
import com.nori.tc.ui.core.coordination.model.UiOperationView;
import com.nori.tc.ui.core.coordination.port.UiOperationLedgerPort;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.model.UiCommandReplyProof;
import com.nori.tc.ui.domain.task.UiTaskStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §13.5 reply ingress 의 <b>거부 조건</b>을 고정합니다.
 *
 * <p>proof 를 언제 받아들이는가보다 <b>언제 받아들이면 안 되는가</b>가 중요합니다 — 늦게 도착한 stale
 * 응답이나 소유권을 잃은 session 의 응답이 통과하면, 이미 새 revision 으로 넘어간 operation 이
 * 옛 결과로 확정됩니다.</p>
 */
class UiReplyProofIngestServiceTest {

    private static final String OPERATION_ID = "op-1";
    private static final String DIGEST = "a".repeat(64);
    private static final String SESSION = "tc-gateway:pod-a:boot-a";

    @Test
    @DisplayName("revision/digest 가 현재 operation 과 다르면 proof 를 기록하지 않는다")
    void staleRevisionOrDigestIsRejected() {
        final RecordingLedger ledger = new RecordingLedger(UiOperationPhase.APPLYING);
        final UiReplyProofIngestService service = service(ledger);

        assertFalse(service.ingest(reply(new UiCommandReplyProof(
                OPERATION_ID, UiCommandReplyProof.STATE_APPLIED, SESSION, 3L, 5L, 5L, DIGEST)), 1_000L));
        assertFalse(service.ingest(reply(new UiCommandReplyProof(
                OPERATION_ID, UiCommandReplyProof.STATE_APPLIED, SESSION, 3L, 7L, 7L, "b".repeat(64))), 1_000L));
        assertTrue(ledger.acks.isEmpty());
    }

    @Test
    @DisplayName("소유권을 잃은 session 의 응답은 성공으로도 실패로도 쓰지 않는다")
    void staleFencedReplyIsNeitherSuccessNorFailure() {
        final RecordingLedger ledger = new RecordingLedger(UiOperationPhase.APPLYING);

        assertFalse(service(ledger).ingest(reply(new UiCommandReplyProof(
                OPERATION_ID, UiCommandReplyProof.STATE_STALE_FENCED, SESSION, 3L, 7L, 7L, DIGEST)), 1_000L));
        assertTrue(ledger.acks.isEmpty());
        assertTrue(ledger.aborts.isEmpty());
    }

    @Test
    @DisplayName("commit 전 도착한 APPLIED 응답은 prepared proof 로만 기록된다")
    void appliedReplyBeforeCommitBecomesPreparedProof() {
        final RecordingLedger ledger = new RecordingLedger(UiOperationPhase.PREPARING);

        assertTrue(service(ledger).ingest(reply(new UiCommandReplyProof(
                OPERATION_ID, UiCommandReplyProof.STATE_APPLIED, SESSION, 3L, 7L, 7L, DIGEST)), 1_000L));
        assertEquals(List.of("prepared"), ledger.acks,
                "commit 전에 applied proof 를 받으면 원장 phase 계약이 깨집니다.");
    }

    @Test
    @DisplayName("commit 후 도착한 APPLIED 응답은 applied proof 로 기록된다")
    void appliedReplyAfterCommitBecomesAppliedProof() {
        final RecordingLedger ledger = new RecordingLedger(UiOperationPhase.APPLYING);

        assertTrue(service(ledger).ingest(reply(new UiCommandReplyProof(
                OPERATION_ID, UiCommandReplyProof.STATE_APPLIED, SESSION, 3L, 7L, 7L, DIGEST)), 1_000L));
        assertEquals(List.of("applied"), ledger.acks);
    }

    @Test
    @DisplayName("commit 전 REJECTED 응답은 operation 을 abort 로 확정한다")
    void rejectedReplyBeforeCommitAbortsTheOperation() {
        final RecordingLedger ledger = new RecordingLedger(UiOperationPhase.PREPARING);

        assertTrue(service(ledger).ingest(reply(new UiCommandReplyProof(
                OPERATION_ID, UiCommandReplyProof.STATE_REJECTED, SESSION, 3L, null, 7L, DIGEST)), 1_000L));
        assertEquals(List.of("PLC_SPEC_INVALID"), ledger.aborts);
    }

    @Test
    @DisplayName("proof 없는 v1 응답은 조용히 무시된다")
    void v1ReplyWithoutProofIsIgnored() {
        final RecordingLedger ledger = new RecordingLedger(UiOperationPhase.APPLYING);
        final UiCommandReply v1 = new UiCommandReply(
                "trace", "TC-GATEWAY-APP", "EQP_UPDATE_REP", "EQP001", "SOCKET",
                UiTaskStatus.PASS, null, null);

        assertFalse(service(ledger).ingest(v1, 1_000L));
        assertTrue(ledger.acks.isEmpty());
    }

    private UiReplyProofIngestService service(final RecordingLedger ledger) {
        final UiCoordinationMetrics metrics = new UiCoordinationMetrics(new SimpleMeterRegistry());
        final UiOperationExecutor executor = new UiOperationExecutor(
                session(), new UiCoordinationProperties(), ledger, headPort(), leasePort(), membership(),
                publisher(), (operationId, manifest, desiredRevision) ->
                        com.nori.tc.ui.core.coordination.port.UiOperationDbFinalizationPort.Outcome.FINALIZED,
                com.nori.tc.ui.core.coordination.port.UiFinalizedRevisionProvider.absent(),
                new UiOperationManifestReader(new tools.jackson.databind.ObjectMapper()), metrics);
        return new UiReplyProofIngestService(ledger, executor, metrics);
    }

    private static UiCommandReply reply(final UiCommandReplyProof proof) {
        return new UiCommandReply(
                "trace", "TC-GATEWAY-APP", "EQP_UPDATE_REP", "EQP001", "SOCKET",
                UiTaskStatus.FAIL, "PLC_SPEC_INVALID", "PLC spec 변환 실패", proof);
    }

    private static UiCoordinationSession session() {
        final UiCoordinationSession session = new UiCoordinationSession(
                "tc-ui-backend", "pod", "boot", "pod");
        session.transitionTo(com.nori.tc.ui.core.coordination.model.UiMembershipState.ACTIVE_SYNCED);
        return session;
    }

    private static com.nori.tc.ui.core.coordination.port.UiResourceHeadPort headPort() {
        return new com.nori.tc.ui.core.coordination.port.UiResourceHeadPort() {
            @Override
            public IssueResult issueCandidate(
                    final String operationId, final List<Candidate> candidates, final long nowEpochMs) {
                return new IssueResult(IssueCode.OK, 1L, null);
            }

            @Override
            public com.nori.tc.ui.core.coordination.model.UiResourceHeadView readHead(
                    final String resourceType, final String resourceId) {
                return new com.nori.tc.ui.core.coordination.model.UiResourceHeadView(
                        resourceType + ":" + resourceId, 0L, 0L, null, null);
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

    private static com.nori.tc.ui.core.coordination.port.UiExecutorLeasePort leasePort() {
        return new com.nori.tc.ui.core.coordination.port.UiExecutorLeasePort() {
            @Override
            public Optional<com.nori.tc.ui.core.coordination.model.UiExecutorLease> tryAcquire(
                    final String operationId, final String sessionId, final long ttlMs, final long nowEpochMs) {
                return Optional.of(new com.nori.tc.ui.core.coordination.model.UiExecutorLease(
                        operationId, sessionId, 1L));
            }

            @Override
            public boolean renew(final com.nori.tc.ui.core.coordination.model.UiExecutorLease lease,
                    final long ttlMs, final long nowEpochMs) {
                return true;
            }

            @Override
            public void release(final com.nori.tc.ui.core.coordination.model.UiExecutorLease lease,
                    final long nowEpochMs) {
                // noop
            }
        };
    }

    private static com.nori.tc.ui.core.coordination.port.UiCoordinationMembershipPort membership() {
        return new com.nori.tc.ui.core.coordination.port.UiCoordinationMembershipPort() {
            @Override
            public Result register(final String sessionId, final String podName, final String protocolVersion,
                    final com.nori.tc.ui.core.coordination.model.UiMembershipState initialState,
                    final long heartbeatTtlMs, final long nowEpochMs) {
                return Result.OK;
            }

            @Override
            public Result renew(final String sessionId, final String protocolVersion,
                    final com.nori.tc.ui.core.coordination.model.UiMembershipState state,
                    final long lastAppliedGlobalRevision, final long heartbeatTtlMs, final long nowEpochMs) {
                return Result.OK;
            }

            @Override
            public List<String> activeSyncedSessionIds(
                    final String serviceName, final String protocolVersion, final long limit) {
                return List.of(SESSION);
            }

            @Override
            public boolean expired(final String serviceName, final String sessionId) {
                return false;
            }
        };
    }

    private static com.nori.tc.ui.core.coordination.port.UiOperationCommandPublisher publisher() {
        return new com.nori.tc.ui.core.coordination.port.UiOperationCommandPublisher() {
            @Override
            public void publishPrepare(final String operationId,
                    final com.nori.tc.ui.core.coordination.model.UiOperationManifestView manifest,
                    final long desiredRevision, final String manifestSha256, final String deadlineAt) {
                // noop
            }

            @Override
            public void publishApply(final String operationId,
                    final com.nori.tc.ui.core.coordination.model.UiOperationManifestView manifest,
                    final long desiredRevision, final String manifestSha256, final String deadlineAt) {
                // noop
            }
        };
    }

    /** proof 기록 종류와 abort 를 기록하는 원장입니다. */
    private static final class RecordingLedger implements UiOperationLedgerPort {

        private static final String MANIFEST_JSON = """
                {"schemaVersion":2,"eventType":"EQP_UPDATE","eqpId":"EQP001",
                 "resourceKeys":["equipment:EQP001"],
                 "descriptors":{"equipment:EQP001":{"schemaVersion":2,"eqpId":"EQP001"}},
                 "expectedBaseRevisions":{"equipment:EQP001":0}}""";

        private final UiOperationPhase phase;
        private final List<String> acks = new ArrayList<>();
        private final List<String> aborts = new ArrayList<>();

        private RecordingLedger(final UiOperationPhase phase) {
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
            return "OK";
        }

        @Override
        public String freezeParticipants(final String operationId, final List<String> participantIds,
                final boolean force, final long nowEpochMs) {
            return "OK";
        }

        @Override
        public String excludeExpiredParticipant(final String operationId, final String serviceName,
                final String expiredSessionId, final long nowEpochMs) {
            return "OK";
        }

        @Override
        public String closeBarrier(
                final String operationId, final List<String> resourceHeadKeys, final long nowEpochMs) {
            return "MISSING_PREPARE";
        }

        @Override
        public String commitHead(final String operationId, final List<String> resourceHeadKeys,
                final List<Long> expectedBaseRevisions, final long nowEpochMs) {
            return "OK";
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
            acks.add(applied ? "applied" : "prepared");
            return "OK";
        }

        @Override
        public Optional<UiOperationView> readOperation(final String operationId) {
            return Optional.of(new UiOperationView(
                    operationId, null, "EQP001", "EQP_UPDATE", UiOperationState.PENDING, phase,
                    List.of("equipment:EQP001"), List.of(SESSION), DIGEST, 0L, 7L, false, null,
                    null, null, null, 1L, 1L, null));
        }

        @Override
        public java.util.List<com.nori.tc.ui.core.coordination.model.UiParticipantProofView> readParticipantProofs(final String operationId) {
            return java.util.List.of();
        }

        @Override
        public java.util.List<com.nori.tc.ui.core.coordination.model.UiPhaseTransitionView> readPhaseHistory(final String operationId) {
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
