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
import com.nori.tc.ui.core.coordination.port.UiGatewayParticipantResolver;
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
    private static final String GATEWAY_MANIFEST_JSON = """
            {"schemaVersion":2,"eventType":"EQP_UPDATE","eqpId":"EQP001","requiresGatewayParticipant":true,
             "resourceKeys":["equipment:EQP001"],
             "descriptors":{"equipment:EQP001":{"schemaVersion":2,"eqpId":"EQP001","routePartition":3}},
             "expectedBaseRevisions":{"equipment:EQP001":0}}""";
    private static final String CREATE_MANIFEST_JSON = """
            {"schemaVersion":2,"eventType":"EQP_CREATE","eqpId":"EQP001","requiresGatewayParticipant":false,
             "resourceKeys":["equipment:EQP001"],
             "descriptors":{"equipment:EQP001":{"schemaVersion":2,"eqpId":"EQP001"}},
             "expectedBaseRevisions":{"equipment:EQP001":0},
             "previousDescriptors":{"equipment:EQP001":{"schemaVersion":2,"eqpId":"EQP001","deleted":true}},
             "dbCommandKind":"CREATE","dbCommand":{"actor":"tester"}}""";

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
                CompensationIssuerFixture.issuer(ledgerPort, resourceHeadPort()),
                com.nori.tc.ui.core.coordination.port.UiOperationApplyDriver.none(),
                UiGatewayParticipantResolver.none(),
                new UiCoordinationMetrics(new SimpleMeterRegistry()));

        assertEquals(0, executor.advance(OPERATION_ID, 1_000L));
        assertTrue(ledgerPort.frozen.isEmpty(),
                "빈 집합으로 고정하면 아무도 적용하지 않은 operation 이 곧바로 PASS 조건을 만족한다.");
    }

    @Test
    @DisplayName("§11.3 — Gateway participant 는 route partition 을 소유한 Pod 하나만 고정한다")
    void onlyRouteOwnerGatewaySessionBecomesParticipant() {
        final RecordingLedgerPort ledgerPort = new RecordingLedgerPort(UiOperationPhase.ACCEPTED);
        final UiOperationExecutor executor = gatewayParticipantExecutor(
                ledgerPort,
                membership(List.of("tc-business:pod-a:boot-a",
                        "tc-gateway:pod-g1:boot-1", "tc-gateway:pod-g2:boot-2", "tc-gateway:pod-g3:boot-3")),
                manifest -> List.of("tc-gateway:pod-g2:boot-2"));

        executor.advance(OPERATION_ID, 1_000L);

        assertEquals(
                List.of(List.of("tc-business:pod-a:boot-a", "tc-gateway:pod-g2:boot-2")),
                ledgerPort.frozen,
                "Gateway 는 명령 토픽을 고정 partition assign 으로 구독하므로 비소유 Pod 은 그 명령을 "
                        + "받지 못한다 — 참여자로 고정하면 barrier 가 영구히 닫히지 않는다.");
    }

    @Test
    @DisplayName("§11.3 — Gateway 소유자를 특정할 수 없으면 participant 를 고정하지 않는다")
    void unresolvedGatewayOwnerBlocksFreeze() {
        final RecordingLedgerPort ledgerPort = new RecordingLedgerPort(UiOperationPhase.ACCEPTED);
        final UiOperationExecutor executor = gatewayParticipantExecutor(
                ledgerPort,
                membership(List.of("tc-business:pod-a:boot-a", "tc-gateway:pod-g1:boot-1")),
                UiGatewayParticipantResolver.none());

        executor.advance(OPERATION_ID, 1_000L);

        assertTrue(ledgerPort.frozen.isEmpty(),
                "Gateway 참여가 필요한 operation 을 Business 만으로 고정하면 Gateway 메모리가 뒤처진 채 "
                        + "operation 이 PASS 한다.");
    }

    @Test
    @DisplayName("§11.3 — lease 소유 토큰이 ACTIVE_SYNCED membership 에 없으면 고정하지 않는다")
    void ownerOutsideActiveSyncedMembershipBlocksFreeze() {
        final RecordingLedgerPort ledgerPort = new RecordingLedgerPort(UiOperationPhase.ACCEPTED);
        final UiOperationExecutor executor = gatewayParticipantExecutor(
                ledgerPort,
                membership(List.of("tc-business:pod-a:boot-a", "tc-gateway:pod-g1:boot-1")),
                // 레거시 lease 스키마의 소유 토큰은 session 식별자가 아니다(§12.2).
                manifest -> List.of("tc-gateway-5bdc8cdc4d-g8zs5-c7e1699b"));

        executor.advance(OPERATION_ID, 1_000L);

        assertTrue(ledgerPort.frozen.isEmpty(),
                "membership 에 없는 문자열을 참여자로 넣으면 아무도 증적을 쓸 수 없는 집합이 고정된다.");
    }

    @Test
    @DisplayName("§13.4 — PREPARE 발행이 실패하면 phase 를 ACCEPTED 로 되돌린다")
    void failedPreparePublishRollsBackToAccepted() {
        final RecordingLedgerPort ledgerPort = new RecordingLedgerPort(UiOperationPhase.ACCEPTED);
        ledgerPort.transitionCode = "OK";
        final UiOperationExecutor executor = executorWithPublisher(ledgerPort, new ThrowingPublisher());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> executor.advance(OPERATION_ID, 1_000L));

        assertEquals(List.of("PREPARING", "ACCEPTED"), ledgerPort.transitions,
                "PREPARING 으로 남기면 PREPARE 를 아무도 재발행하지 않아 operation 이 claim 을 쥔 채 영구히 남는다.");
    }

    @Test
    @DisplayName("§13.6 — APPLY 발행이 실패하면 phase 를 COMMITTED 로 되돌린다")
    void failedApplyPublishRollsBackToCommitted() {
        final RecordingLedgerPort ledgerPort = new RecordingLedgerPort(UiOperationPhase.COMMITTED);
        ledgerPort.transitionCode = "OK";
        final UiOperationExecutor executor = executorWithPublisher(ledgerPort, new ThrowingPublisher());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> executor.advance(OPERATION_ID, 1_000L));

        assertEquals(List.of("APPLYING", "COMMITTED"), ledgerPort.transitions,
                "APPLYING 으로 남기면 기본 APPLY 명령이 재발행되지 않아 applied proof 를 영원히 기다린다.");
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
                CompensationIssuerFixture.issuer(ledgerPort, resourceHeadPort()),
                com.nori.tc.ui.core.coordination.port.UiOperationApplyDriver.none(),
                UiGatewayParticipantResolver.none(),
                new UiCoordinationMetrics(new SimpleMeterRegistry()));

        executor.advance(OPERATION_ID, 1_000L);

        assertEquals(List.of("LAST_PARTICIPANT_EXPIRED"), ledgerPort.repairReasons,
                "확정하지 않으면 operation 이 terminal 에 못 가고 그 설비 barrier 가 영구히 닫힌 채 남는다.");
    }

    @Test
    @DisplayName("§13.8 — 커밋 후 participant 사망은 보상을 발급하고, DB 상태 불명은 발급하지 않는다")
    void compensationIsIssuedOnlyForRecoverableRepairReasons() {
        final RecordingLedgerPort ledgerPort = new RecordingLedgerPort(UiOperationPhase.APPLYING);
        final UiCompensationIssuer issuer = org.mockito.Mockito.mock(UiCompensationIssuer.class);
        final UiOperationExecutor executor = new UiOperationExecutor(
                session(), properties(), ledgerPort, resourceHeadPort(), new RecordingLeasePort(true),
                emptyMembership(), new RecordingPublisher(), finalizer(), UiFinalizedRevisionProvider.absent(),
                new UiOperationManifestReader(new ObjectMapper()), issuer,
                com.nori.tc.ui.core.coordination.port.UiOperationApplyDriver.none(),
                UiGatewayParticipantResolver.none(),
                new UiCoordinationMetrics(new SimpleMeterRegistry()));
        final UiOperationView operation = ledgerPort.readOperation(OPERATION_ID).orElseThrow();

        executor.markRepairRequired(operation, "LAST_PARTICIPANT_EXPIRED", "죽음", 1_000L);
        org.mockito.Mockito.verify(issuer).issueFor(operation, 1_000L);

        /*
         * DB 의 실제 상태를 확정하지 못한 경우다. 이전 상태로 되돌리는 것도 "모르는 상태를 덮어쓰는"
         * 행위이므로 자동으로 하지 않는다(§13.8 마지막 규칙).
         */
        executor.markRepairRequired(operation, "DB_STATE_UNEXPECTED", "불명", 2_000L);
        org.mockito.Mockito.verify(issuer, org.mockito.Mockito.never()).issueFor(operation, 2_000L);
    }

    @Test
    @DisplayName("§7.2 — 보상 operation 이 PASS 하면 되돌린 원 operation 을 FAIL + ROLLED_BACK 으로 확정한다")
    void passedCompensationClosesTheOperationItReverted() {
        final RecordingLedgerPort ledgerPort = new RecordingLedgerPort(UiOperationPhase.FINALIZING);
        ledgerPort.compensatesOperationId = "op-failed";
        final UiOperationExecutor executor = newExecutor(session(), ledgerPort, new RecordingLeasePort(true));

        executor.advance(OPERATION_ID, 1_000L);

        /*
         * 고정 phase 대역이라 advance 루프가 같은 단계를 반복한다 — 확정이 여러 번 시도되는 것은
         * 대역의 성질이고, 실제 원장은 같은 보상 완료의 재적용을 멱등으로 받는다
         * (CompensationClaimHandoverTest). 여기서 고정할 계약은 "닫은 대상이 이것뿐" 이다.
         */
        assertEquals(java.util.Set.of("op-failed<-" + OPERATION_ID), java.util.Set.copyOf(ledgerPort.rolledBack),
                "확정하지 않으면 되돌리기가 끝났는데도 원 operation 이 REPAIR_REQUIRED 로 남아 "
                        + "운영자가 이미 복구된 설비를 수동 복구 대상으로 본다.");
    }

    @Test
    @DisplayName("§7.2 — 보상이 아닌 operation 의 PASS 는 다른 operation 을 건드리지 않는다")
    void ordinaryPassDoesNotCloseAnythingElse() {
        final RecordingLedgerPort ledgerPort = new RecordingLedgerPort(UiOperationPhase.FINALIZING);
        final UiOperationExecutor executor = newExecutor(session(), ledgerPort, new RecordingLeasePort(true));

        executor.advance(OPERATION_ID, 1_000L);

        assertTrue(ledgerPort.rolledBack.isEmpty());
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

    /**
     * 주어진 publisher 로 executor 를 만듭니다.
     */
    private UiOperationExecutor executorWithPublisher(
            final RecordingLedgerPort ledgerPort,
            final UiOperationCommandPublisher publisher
    ) {
        return new UiOperationExecutor(
                session(), properties(), ledgerPort, resourceHeadPort(), new RecordingLeasePort(true),
                membership(), publisher, finalizer(), UiFinalizedRevisionProvider.absent(),
                new UiOperationManifestReader(new ObjectMapper()),
                CompensationIssuerFixture.issuer(ledgerPort, resourceHeadPort()),
                com.nori.tc.ui.core.coordination.port.UiOperationApplyDriver.none(),
                UiGatewayParticipantResolver.none(),
                new UiCoordinationMetrics(new SimpleMeterRegistry()));
    }

    /**
     * Gateway 참여가 필요한 manifest 를 읽는 executor 를 만듭니다.
     */
    private UiOperationExecutor gatewayParticipantExecutor(
            final RecordingLedgerPort ledgerPort,
            final UiCoordinationMembershipPort membershipPort,
            final UiGatewayParticipantResolver gatewayParticipantResolver
    ) {
        ledgerPort.manifestJson = GATEWAY_MANIFEST_JSON;
        return new UiOperationExecutor(
                session(), properties(), ledgerPort, resourceHeadPort(), new RecordingLeasePort(true),
                membershipPort, new RecordingPublisher(), finalizer(), UiFinalizedRevisionProvider.absent(),
                new UiOperationManifestReader(new ObjectMapper()),
                CompensationIssuerFixture.issuer(ledgerPort, resourceHeadPort()),
                com.nori.tc.ui.core.coordination.port.UiOperationApplyDriver.none(),
                gatewayParticipantResolver,
                new UiCoordinationMetrics(new SimpleMeterRegistry()));
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
                CompensationIssuerFixture.issuer(ledgerPort, resourceHeadPort()),
                com.nori.tc.ui.core.coordination.port.UiOperationApplyDriver.none(),
                UiGatewayParticipantResolver.none(),
                new UiCoordinationMetrics(new SimpleMeterRegistry()));
    }

    @Test
    @DisplayName("§16.1 — 설비 생성은 PREPARE 를 발행하기 전에 DB 임시 행을 준비한다")
    void createStagesTheDbRowBeforePublishingPrepare() {
        final RecordingLedgerPort ledgerPort = new RecordingLedgerPort(UiOperationPhase.ACCEPTED);
        ledgerPort.manifestJson = CREATE_MANIFEST_JSON;
        ledgerPort.transitionCode = "OK";
        final List<String> order = new ArrayList<>();

        stagingExecutor(ledgerPort, order, UiOperationDbFinalizationPort.Outcome.FINALIZED)
                .advance(OPERATION_ID, 1_000L);

        /*
         * Gateway 는 equipmentProfile.eqpKey 를 양수로 요구하고 적용 단계에서 tc_eqp(eqp_key) 를
         * 참조하는 상태 이력을 남긴다. 행이 없는 상태로 PREPARE 를 내보내면 Gateway 는 이 operation 을
         * 절대 통과시킬 수 없다.
         */
        // 원장 대역이 phase 를 ACCEPTED 로 고정하므로 같은 패스가 반복된다 — 첫 순서만 본다.
        assertEquals(List.of("stage", "publishPrepare"), order.subList(0, 2));
    }

    @Test
    @DisplayName("§16.1 — 목표와 다른 행이 이미 있으면 커밋 전 abort 로 끝내고 임시 행을 되돌린다")
    void unexpectedExistingRowAbortsBeforeCommit() {
        final RecordingLedgerPort ledgerPort = new RecordingLedgerPort(UiOperationPhase.ACCEPTED);
        ledgerPort.manifestJson = CREATE_MANIFEST_JSON;
        final List<String> order = new ArrayList<>();

        stagingExecutor(ledgerPort, order, UiOperationDbFinalizationPort.Outcome.UNEXPECTED_STATE)
                .advance(OPERATION_ID, 1_000L);

        assertEquals("DB_STAGE_UNEXPECTED", ledgerPort.aborts.get(0));
        assertTrue(order.contains("unstage"), "abort 는 임시 DB 상태를 남기지 않아야 합니다(§16.1).");
        assertFalse(order.contains("publishPrepare"), "어긋난 상태로 명령을 내보내면 안 됩니다.");
    }

    @Test
    @DisplayName("§7.2 — staging 이 결정적으로 거부되면 무한 재시도하지 않고 커밋 전 abort 한다")
    void rejectedStagingAbortsBeforeCommitInsteadOfRetryingForever() {
        final RecordingLedgerPort ledgerPort = new RecordingLedgerPort(UiOperationPhase.ACCEPTED);
        ledgerPort.manifestJson = CREATE_MANIFEST_JSON;
        final List<String> order = new ArrayList<>();

        stagingExecutor(ledgerPort, order, UiOperationDbFinalizationPort.Outcome.REJECTED)
                .advance(OPERATION_ID, 1_000L);

        assertEquals("DB_STAGE_REJECTED", ledgerPort.aborts.get(0));
        assertTrue(order.contains("unstage"), "abort 는 임시 DB 상태를 남기지 않아야 합니다(§16.1).");
        assertFalse(order.contains("publishPrepare"), "거부된 요청으로 명령을 내보내면 안 됩니다.");
    }

    private static UiOperationExecutor stagingExecutor(
            final RecordingLedgerPort ledgerPort,
            final List<String> order,
            final UiOperationDbFinalizationPort.Outcome stageOutcome
    ) {
        return new UiOperationExecutor(
                session(), properties(), ledgerPort, resourceHeadPort(), new RecordingLeasePort(true),
                membership(), new OrderRecordingPublisher(order),
                new OrderRecordingFinalizer(order, stageOutcome), UiFinalizedRevisionProvider.absent(),
                new UiOperationManifestReader(new ObjectMapper()),
                CompensationIssuerFixture.issuer(ledgerPort, resourceHeadPort()),
                com.nori.tc.ui.core.coordination.port.UiOperationApplyDriver.none(),
                UiGatewayParticipantResolver.none(),
                new UiCoordinationMetrics(new SimpleMeterRegistry()));
    }

    /** 준비/되돌리기 호출 순서를 기록하는 확정기입니다. */
    private static final class OrderRecordingFinalizer implements UiOperationDbFinalizationPort {

        private final List<String> order;
        private final Outcome stageOutcome;

        private OrderRecordingFinalizer(final List<String> order, final Outcome stageOutcome) {
            this.order = order;
            this.stageOutcome = stageOutcome;
        }

        @Override
        public Outcome finalizeToTarget(
                final String operationId, final UiOperationManifestView manifest, final long desiredRevision) {
            order.add("finalize");
            return Outcome.FINALIZED;
        }

        @Override
        public Outcome stageBeforePrepare(final String operationId, final UiOperationManifestView manifest) {
            order.add("stage");
            return stageOutcome;
        }

        @Override
        public void unstageAfterAbort(final String operationId, final UiOperationManifestView manifest) {
            order.add("unstage");
        }
    }

    /** 발행 순서를 기록하는 publisher 입니다. */
    private static final class OrderRecordingPublisher implements UiOperationCommandPublisher {

        private final List<String> order;

        private OrderRecordingPublisher(final List<String> order) {
            this.order = order;
        }

        @Override
        public void publishPrepare(final String operationId, final UiOperationManifestView manifest,
                final long desiredRevision, final String manifestSha256, final String deadlineAt) {
            order.add("publishPrepare");
        }

        @Override
        public void publishApply(final String operationId, final UiOperationManifestView manifest,
                final long desiredRevision, final String manifestSha256, final String deadlineAt) {
            order.add("publishApply");
        }
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
        properties.setGatewayServiceName("tc-gateway");
        return properties;
    }

    private static UiResourceHeadPort resourceHeadPort() {
        return new UiResourceHeadPort() {
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
                // 실제 원장은 serviceName namespace 단위로 조회한다 — 섞어 돌려주면 참여자 판정을 못 본다.
                return sessions.stream().filter(id -> id.startsWith(serviceName + ":")).toList();
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

    /** 발행이 항상 실패하는 publisher 입니다 — 브로커 장애/계약 위반을 재현합니다. */
    private static final class ThrowingPublisher implements UiOperationCommandPublisher {
        @Override
        public void publishPrepare(final String operationId, final UiOperationManifestView manifest,
                final long desiredRevision, final String manifestSha256, final String deadlineAt) {
            throw new IllegalStateException("publish failed");
        }

        @Override
        public void publishApply(final String operationId, final UiOperationManifestView manifest,
                final long desiredRevision, final String manifestSha256, final String deadlineAt) {
            throw new IllegalStateException("publish failed");
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
        private final List<String> rolledBack = new ArrayList<>();
        private String compensatesOperationId;
        private String excludeCode = "NOT_EXPIRED";
        private String manifestJson = MANIFEST_JSON;
        private String transitionCode = "PHASE_MISMATCH";

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
            return transitionCode;
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
        public String markRolledBack(final String operationId, final String compensationOperationId,
                final long nowEpochMs) {
            rolledBack.add(operationId + "<-" + compensationOperationId);
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
                    "a".repeat(64), 0L, 1L, false,
                    compensatesOperationId == null ? null : 1L, compensatesOperationId,
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
            return Optional.of(manifestJson);
        }

        @Override
        public List<String> pendingOperationIds(final long limit) {
            return List.of(OPERATION_ID);
        }
    }
}
