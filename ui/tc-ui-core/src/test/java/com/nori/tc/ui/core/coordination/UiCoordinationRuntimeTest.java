package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiMembershipState;
import com.nori.tc.ui.core.coordination.port.UiCoordinationMembershipPort;
import com.nori.tc.ui.core.coordination.port.UiCoordinationProbePort;
import com.nori.tc.ui.core.coordination.port.UiOperationLedgerPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §9/§13.11 — 기동 게이트와 readiness 판정을 고정합니다.
 *
 * <p>heartbeat 주기 동작은 시간 의존이라 여기서 다루지 않고, <b>기동을 막아야 하는 조건</b>과
 * <b>Ready 로 두면 안 되는 조건</b>만 봅니다. 이 둘이 무너지면 원장을 모르는 Pod 가 mutation 을
 * 받아들입니다.</p>
 */
class UiCoordinationRuntimeTest {

    @Test
    @DisplayName("TTL 이 만료된 적 있는 sessionId 로는 기동할 수 없다")
    void fencedSessionCannotStart() {
        final UiCoordinationRuntime runtime = runtime(
                membership(UiCoordinationMembershipPort.Result.FENCED), probe(true, true), 0);

        final IllegalStateException failure = assertThrows(IllegalStateException.class, runtime::start);
        assertTrue(failure.getMessage().contains("되살릴 수 없습니다"));
    }

    @Test
    @DisplayName("atomic script 검증에 실패하면 기동하지 않는다")
    void scriptProbeFailureBlocksStartup() {
        final UiCoordinationRuntime runtime = runtime(
                membership(UiCoordinationMembershipPort.Result.OK), probe(false, true), 0);

        assertThrows(IllegalStateException.class, runtime::start);
    }

    @Test
    @DisplayName("정상 기동은 ACTIVE_SYNCED 로 전이하고 mutation readiness 를 연다")
    void normalStartupBecomesActiveSynced() {
        final UiCoordinationRuntime runtime = runtime(
                membership(UiCoordinationMembershipPort.Result.OK), probe(true, true), 0);

        runtime.start();
        try {
            assertEquals(UiMembershipState.ACTIVE_SYNCED, runtime.membershipState());
            assertTrue(runtime.readyForMutation());
            assertTrue(runtime.coordinationEnabled());
            assertEquals(true, runtime.details().get("coordinationEnabled"));
        } finally {
            runtime.stop();
        }
    }

    @Test
    @DisplayName("cluster epoch 이 바뀌면 Ready 가 아니다")
    void clusterEpochMismatchIsNotReady() {
        final UiCoordinationRuntime runtime = runtime(
                membership(UiCoordinationMembershipPort.Result.OK), probe(true, false), 0);

        runtime.start();
        try {
            assertFalse(runtime.readyForMutation(),
                    "epoch 이 바뀐 뒤에도 Ready 로 두면 다른 클러스터의 원장을 자기 것으로 쓰게 됩니다.");
        } finally {
            runtime.stop();
        }
    }

    @Test
    @DisplayName("복구 backlog 가 안전 한도를 넘으면 Ready 가 아니다")
    void recoveryBacklogOverLimitIsNotReady() {
        final UiCoordinationRuntime runtime = runtime(
                membership(UiCoordinationMembershipPort.Result.OK), probe(true, true), 500);

        runtime.start();
        try {
            assertFalse(runtime.readyForMutation());
        } finally {
            runtime.stop();
        }
    }

    @Test
    @DisplayName("catalog head 가 어긋난 Pod 는 Ready 가 아니다")
    void catalogMismatchIsNotReady() {
        final UiCoordinationProperties properties = properties();
        final UiCoordinationMetrics sharedMetrics = new UiCoordinationMetrics(new SimpleMeterRegistry());
        final UiCoordinationRuntime runtime = new UiCoordinationRuntime(
                session(), properties, membership(UiCoordinationMembershipPort.Result.OK), probe(true, true),
                reconciler(properties, sharedMetrics, 0), outOfSyncScenarioGuard(),
                UiPermissionCatalogGuard.disabled(), sharedMetrics);

        runtime.start();
        try {
            assertFalse(runtime.readyForMutation());
            assertEquals(false, runtime.details().get("scenarioCatalogInSync"));
        } finally {
            runtime.stop();
        }
    }

    private UiCoordinationRuntime runtime(
            final UiCoordinationMembershipPort membershipPort,
            final UiCoordinationProbePort probePort,
            final int pendingCount
    ) {
        final UiCoordinationProperties properties = properties();
        final UiCoordinationMetrics metrics = new UiCoordinationMetrics(new SimpleMeterRegistry());
        return new UiCoordinationRuntime(
                session(), properties, membershipPort, probePort, reconciler(properties, metrics, pendingCount),
                UiScenarioCatalogGuard.disabled(), UiPermissionCatalogGuard.disabled(), metrics);
    }

    private static UiCoordinationProperties properties() {
        final UiCoordinationProperties properties = new UiCoordinationProperties();
        properties.setHeartbeatIntervalMs(60_000L);
        properties.setRecoveryBacklogLimit(10);
        return properties;
    }

    private static UiCoordinationSession session() {
        return new UiCoordinationSession("tc-ui-backend", "pod-uid", "boot-id", "pod-name");
    }

    /**
     * 실제 reconciler 를 쓰되 executor 의 session 을 fence 해 전이가 일어나지 않게 합니다.
     *
     * <p>여기서 보려는 것은 backlog 집계와 readiness 판정이지 전이가 아니므로, 전이를 막아 두는 편이
     * 실패 원인을 좁힙니다.</p>
     */
    private static UiOperationReconciler reconciler(
            final UiCoordinationProperties properties,
            final UiCoordinationMetrics metrics,
            final int pendingCount
    ) {
        final UiCoordinationSession fenced =
                new UiCoordinationSession("tc-ui-backend", "other-pod", "other-boot", "other");
        fenced.fence();
        final UiOperationLedgerPort ledger = pendingLedger(pendingCount);
        final UiOperationExecutor executor = new UiOperationExecutor(
                fenced, properties, ledger, headPort(), leasePort(),
                membership(UiCoordinationMembershipPort.Result.OK), publisher(),
                (operationId, manifest, desiredRevision) ->
                        com.nori.tc.ui.core.coordination.port.UiOperationDbFinalizationPort.Outcome.FINALIZED,
                com.nori.tc.ui.core.coordination.port.UiFinalizedRevisionProvider.absent(),
                new UiOperationManifestReader(new tools.jackson.databind.ObjectMapper()),
                CompensationIssuerFixture.issuer(ledger, headPort()),
                com.nori.tc.ui.core.coordination.port.UiOperationApplyDriver.none(),
                com.nori.tc.ui.core.coordination.port.UiGatewayParticipantResolver.none(), metrics);
        return new UiOperationReconciler(ledger, executor, properties, metrics);
    }

    private static com.nori.tc.ui.core.coordination.port.UiResourceHeadPort headPort() {
        return new com.nori.tc.ui.core.coordination.port.UiResourceHeadPort() {
            @Override
            public IssueResult issueCompensationCandidate(
                    final String operationId, final String compensatesOperationId,
                    final List<Candidate> candidates, final long nowEpochMs) {
                return issueCandidate(operationId, candidates, nowEpochMs);
            }

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
                return Optional.empty();
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

    private static UiScenarioCatalogGuard outOfSyncScenarioGuard() {
        return new UiScenarioCatalogGuard() {
            @Override
            public void onCatalogApplied(final String catalogChecksum) {
                // noop
            }

            @Override
            public void requirePlanningAllowed(final String catalogChecksum) {
                throw new UiCatalogNotSyncedException("deploy-scenario", "mismatch");
            }

            @Override
            public boolean inSync() {
                return false;
            }

            @Override
            public long headRevision() {
                return 3L;
            }
        };
    }

    private static UiCoordinationProbePort probe(final boolean scriptOk, final boolean epochUnchanged) {
        return new UiCoordinationProbePort() {
            @Override
            public boolean verifyAtomicScriptReadWrite() {
                return scriptOk;
            }

            @Override
            public boolean clusterEpochUnchanged() {
                return epochUnchanged;
            }
        };
    }

    private static UiCoordinationMembershipPort membership(final UiCoordinationMembershipPort.Result result) {
        final AtomicBoolean registered = new AtomicBoolean(false);
        return new UiCoordinationMembershipPort() {
            @Override
            public Result register(final String sessionId, final String podName, final String protocolVersion,
                    final UiMembershipState initialState, final long heartbeatTtlMs, final long nowEpochMs) {
                registered.set(true);
                return result;
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
                return List.of();
            }

            @Override
            public boolean expired(final String serviceName, final String sessionId) {
                return false;
            }
        };
    }

    private static UiOperationLedgerPort pendingLedger(final int pendingCount) {
        return new UiOperationLedgerPort() {
            @Override
            public CreateResult createOperation(final String operationId, final String manifestSha256,
                    final String manifestJson, final Map<String, String> extraFields, final long nowEpochMs) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String transition(final String operationId,
                    final Set<com.nori.tc.ui.core.coordination.model.UiOperationPhase> expectedPhases,
                    final com.nori.tc.ui.core.coordination.model.UiOperationPhase newPhase,
                    final com.nori.tc.ui.core.coordination.model.UiOperationState newState,
                    final String executorSessionId, final Long executorEpoch, final String errorCode,
                    final String errorMsg, final String repairReason, final long nowEpochMs) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String freezeParticipants(final String operationId, final List<String> participantIds,
                    final boolean force, final long nowEpochMs) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String excludeExpiredParticipant(final String operationId, final String serviceName,
                    final String expiredSessionId, final long nowEpochMs) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String closeBarrier(
                    final String operationId, final List<String> resourceHeadKeys, final long nowEpochMs) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String commitHead(final String operationId, final List<String> resourceHeadKeys,
                    final List<Long> expectedBaseRevisions, final long nowEpochMs) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int checkApplied(
                    final String operationId, final boolean advanceToFinalizing, final long nowEpochMs) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String recordDbFinalized(final String operationId, final String executorSessionId,
                    final long executorEpoch, final long finalizedRevision, final long nowEpochMs) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String openBarrierAndPass(
                    final String operationId, final List<String> resourceHeadKeys, final long nowEpochMs) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String abort(final String operationId, final List<String> resourceHeadKeys,
                    final String errorCode, final String errorMsg, final long nowEpochMs) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String markRolledBack(final String operationId, final String compensationOperationId,
                    final long nowEpochMs) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String ackProof(final String operationId, final String participantId, final boolean applied,
                    final long revision, final String digest, final String ownerLeaseKey, final Long ownerEpoch,
                    final String proofJson, final long nowEpochMs) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<com.nori.tc.ui.core.coordination.model.UiOperationView> readOperation(
                    final String operationId) {
                return Optional.empty();
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
                return Optional.empty();
            }

            @Override
            public List<String> pendingOperationIds(final long limit) {
                return java.util.Collections.nCopies(pendingCount, "op");
            }
        };
    }
}
