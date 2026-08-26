package com.nori.tc.ui.adapters.redis.coordination;

import com.nori.tc.common.coordination.CoordinationKeys;
import com.nori.tc.common.coordination.script.CoordinationScriptExecutor;
import com.nori.tc.common.coordination.store.ClusterEpochRedisStore;
import com.nori.tc.ui.adapters.redis.support.AbstractRedisContainerTest;
import com.nori.tc.ui.core.coordination.UiCoordinationProperties;
import com.nori.tc.ui.core.coordination.UiCoordinationSession;
import com.nori.tc.ui.core.coordination.UiOperationAcceptanceService;
import com.nori.tc.ui.core.coordination.UiOperationExecutor;
import com.nori.tc.ui.core.coordination.UiCompensationIssuer;
import com.nori.tc.ui.core.coordination.UiOperationManifestFactory;
import com.nori.tc.ui.core.coordination.UiOperationManifestReader;
import com.nori.tc.ui.core.coordination.UiOperationQueryService;
import com.nori.tc.ui.core.coordination.UiCoordinationMetrics;
import com.nori.tc.ui.core.coordination.UiResourceKeyPlanner;
import com.nori.tc.ui.core.coordination.UiRuntimeDescriptorCodec;
import com.nori.tc.ui.core.coordination.model.UiMembershipState;
import com.nori.tc.ui.core.coordination.model.UiOperationAcceptance;
import com.nori.tc.ui.core.coordination.model.UiOperationPhase;
import com.nori.tc.ui.core.coordination.model.UiOperationRequest;
import com.nori.tc.ui.core.coordination.model.UiOperationState;
import com.nori.tc.ui.core.coordination.model.UiOperationView;
import com.nori.tc.ui.core.coordination.model.UiRuntimeDescriptor;
import com.nori.tc.ui.core.coordination.port.UiCoordinationMembershipPort;
import com.nori.tc.ui.core.coordination.port.UiFinalizedRevisionProvider;
import com.nori.tc.ui.core.coordination.port.UiOperationLedgerPort;
import com.nori.tc.ui.core.coordination.port.UiResourceHeadPort;
import com.nori.tc.ui.core.port.redis.AsyncResultStorePort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §23.2 / §23.3 / §23.4 — operation protocol 을 실 Redis 원장 위에서 participant 시뮬레이터로 완주시킵니다.
 *
 * <p>여기서 검증하는 것은 "executor 가 순서를 지킨다" 가 아니라 <b>부분 조건으로는 PASS 가 불가능하다</b>
 * 는 것입니다 — 한 participant 의 applied proof 만 빠져도 barrier 는 열리지 않고 operation 은 PENDING 에
 * 머물러야 합니다.</p>
 */
class UiOperationProtocolIT extends AbstractRedisContainerTest {

    private static final String PROTOCOL_VERSION = "2";

    private static LettuceConnectionFactory connectionFactory;

    /**
     * 서비스명을 테스트마다 다르게 둡니다 — 컨테이너가 공유 reuse 라 이전 테스트가 등록한 session 이
     * 그대로 살아 있고, participant 산출은 "그 서비스의 모든 ACTIVE_SYNCED session" 이므로 격리하지 않으면
     * 앞 테스트의 session 까지 고정 집합에 들어가 prepare proof 가 영원히 모이지 않습니다.
     */
    private String businessService;
    private String eqpId;
    private String clusterEpoch;
    private UiCoordinationContext context;
    private UiOperationLedgerPort ledgerPort;
    private UiResourceHeadPort resourceHeadPort;
    private UiCoordinationMembershipPort membershipPort;
    private UiOperationAcceptanceService acceptanceService;
    private UiOperationManifestFactory manifestFactory;
    private UiCoordinationProperties properties;
    private RecordingCommandPublisher commandPublisher;
    private RecordingDbFinalizer dbFinalizer;
    private List<String> businessSessions;

    @BeforeAll
    static void createConnectionFactory() {
        connectionFactory = newConnectionFactory();
    }

    @AfterAll
    static void closeConnectionFactory() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        final CoordinationScriptExecutor executor = new CoordinationScriptExecutor(connectionFactory);
        clusterEpoch = new ClusterEpochRedisStore(executor)
                .initializeIfAbsent(UUID.randomUUID().toString()).value();

        context = new UiCoordinationContext(connectionFactory);
        ledgerPort = new RedisUiOperationLedgerAdapter(context.operationLedgerStore());
        resourceHeadPort = new RedisUiResourceHeadAdapter(context.resourceBarrierStore());
        membershipPort = new RedisUiMembershipAdapter(context.membershipStore());

        final ObjectMapper objectMapper = new ObjectMapper();
        manifestFactory = new UiOperationManifestFactory(
                objectMapper, new UiRuntimeDescriptorCodec(objectMapper), new CommonCoordinationDigestAdapter());
        acceptanceService = new UiOperationAcceptanceService(ledgerPort, resourceHeadPort, manifestFactory);

        properties = new UiCoordinationProperties();
        businessService = "tc-business-" + UUID.randomUUID().toString().substring(0, 8);
        properties.setBusinessServiceName(businessService);
        properties.setProtocolVersion(PROTOCOL_VERSION);

        commandPublisher = new RecordingCommandPublisher();
        dbFinalizer = new RecordingDbFinalizer();

        eqpId = "EQP-" + UUID.randomUUID().toString().substring(0, 8);
        businessSessions = List.of(registerBusinessSession(), registerBusinessSession(), registerBusinessSession());
    }

    @Test
    @DisplayName("§23.2 — 전 participant 의 prepared/applied proof 와 DB finalization 이 모두 모여야 PASS 다")
    void allProofsAndDbFinalizationAreRequiredForPass() {
        final UiOperationExecutor executor = newExecutor(newSession());
        final UiOperationAcceptance accepted = accept("KEY-" + UUID.randomUUID());
        assertEquals(UiOperationState.PENDING, accepted.state());

        final String operationId = accepted.operationId();
        final long revision = revisionOf(operationId);
        final String digest = digestOf(operationId);

        // [1] prepare 명령까지만 진행 — prepared proof 가 없으니 barrier 는 닫히지 않는다.
        executor.advance(operationId, System.currentTimeMillis());
        assertEquals(UiOperationPhase.PREPARING, phaseOf(operationId));
        assertTrue(commandPublisher.preparePublished > 0, "prepare 명령이 발행돼야 합니다.");

        // [2] 3 session 중 2 개만 prepared → 여전히 진행 불가.
        ackPrepared(operationId, businessSessions.get(0), revision, digest);
        ackPrepared(operationId, businessSessions.get(1), revision, digest);
        executor.advance(operationId, System.currentTimeMillis());
        assertEquals(UiOperationPhase.PREPARING, phaseOf(operationId));

        // [3] 마지막 prepared proof 가 들어오면 commit 까지 전진한다.
        ackPrepared(operationId, businessSessions.get(2), revision, digest);
        executor.advance(operationId, System.currentTimeMillis());
        assertEquals(UiOperationPhase.APPLYING, phaseOf(operationId));
        assertEquals(revision, headCommittedRevision(), "commit 후 committed head 가 새 revision 이어야 합니다.");
        assertNotEquals(revision, headOpenRevision(), "commit 직후에는 open revision 이 아직 옛 값이어야 합니다.");

        // [4] applied proof 가 하나라도 빠지면 PASS 가 될 수 없다(§15.4).
        ackApplied(operationId, businessSessions.get(0), revision, digest);
        ackApplied(operationId, businessSessions.get(1), revision, digest);
        executor.advance(operationId, System.currentTimeMillis());
        assertEquals(UiOperationState.PENDING, stateOf(operationId));
        assertEquals(0, dbFinalizer.calls, "전원 applied 전에는 DB finalization 을 수행하면 안 됩니다.");

        // [5] 전원 applied → DB finalization → barrier open → PASS.
        ackApplied(operationId, businessSessions.get(2), revision, digest);
        executor.advance(operationId, System.currentTimeMillis());

        assertEquals(UiOperationState.PASS, stateOf(operationId));
        assertEquals(UiOperationPhase.BARRIER_OPEN, phaseOf(operationId));
        assertEquals(1, dbFinalizer.calls);
        assertEquals(revision, headCommittedRevision());
        assertEquals(revision, headOpenRevision(), "PASS 후에는 barrier 가 열려 처리 재개가 가능해야 합니다.");
    }

    @Test
    @DisplayName("§23.3 — 접수 Pod 가 죽어도 다른 Pod 가 lease 를 인계받아 같은 operationId 를 완료한다")
    void anotherInstanceTakesOverAndCompletesTheSameOperation() {
        final UiOperationExecutor firstInstance = newExecutor(newSession());
        final UiOperationAcceptance accepted = accept("KEY-" + UUID.randomUUID());
        final String operationId = accepted.operationId();
        final long revision = revisionOf(operationId);
        final String digest = digestOf(operationId);

        firstInstance.advance(operationId, System.currentTimeMillis());
        for (final String session : businessSessions) {
            ackPrepared(operationId, session, revision, digest);
        }

        /*
         * 접수 Pod 가 lease 를 쥔 채 사망한 상태를 재현한다 — 죽은 Pod 는 release 를 호출하지 못하므로
         * lease 는 TTL 로만 풀린다. 그전에 다른 Pod 가 전이하면 두 Pod 가 같은 operation 을 밀게 된다.
         */
        final RedisUiExecutorLeaseAdapter leaseAdapter = new RedisUiExecutorLeaseAdapter(context.leaseStore());
        assertTrue(leaseAdapter.tryAcquire(operationId, "tc-ui-backend:dead-pod:boot", 600L,
                System.currentTimeMillis()).isPresent());

        final UiOperationExecutor secondInstance = newExecutor(newSession());
        assertEquals(0, secondInstance.advance(operationId, System.currentTimeMillis()),
                "lease 를 쥔 Pod 가 살아 있다고 보이는 동안에는 다른 Pod 가 전이하면 안 됩니다.");
        assertEquals(UiOperationPhase.PREPARING, phaseOf(operationId));

        sleep(700L);

        secondInstance.advance(operationId, System.currentTimeMillis());
        for (final String session : businessSessions) {
            ackApplied(operationId, session, revision, digest);
        }
        secondInstance.advance(operationId, System.currentTimeMillis());

        assertEquals(UiOperationState.PASS, stateOf(operationId),
                "인계 Pod 가 로컬 완료 목록 없이 원장만 보고 같은 operationId 를 끝내야 합니다.");
    }

    @Test
    @DisplayName("§9.2/B18 — 커밋 후 죽은 participant 를 제외하지 않으면 barrier 가 영구히 열리지 않는다")
    void expiredParticipantIsExcludedAfterCommitSoTheBarrierCanOpen() {
        final UiOperationExecutor executor = newExecutor(newSession());
        final UiOperationAcceptance accepted = accept("KEY-" + UUID.randomUUID());
        final String operationId = accepted.operationId();
        final long revision = revisionOf(operationId);
        final String digest = digestOf(operationId);

        executor.advance(operationId, System.currentTimeMillis());
        for (final String session : businessSessions) {
            ackPrepared(operationId, session, revision, digest);
        }
        executor.advance(operationId, System.currentTimeMillis());
        assertEquals(UiOperationPhase.APPLYING, phaseOf(operationId));

        // 한 participant 가 커밋 직후 죽는다 — heartbeat key 를 지워 TTL 만료를 재현한다.
        final String deadSession = businessSessions.get(2);
        expireMember(deadSession);
        ackApplied(operationId, businessSessions.get(0), revision, digest);
        ackApplied(operationId, businessSessions.get(1), revision, digest);

        // 제외가 일어나기 전에는 잔여가 남아 PASS 가 불가능하다.
        assertEquals(UiOperationState.PENDING, stateOf(operationId));

        executor.advance(operationId, System.currentTimeMillis());
        executor.advance(operationId, System.currentTimeMillis());

        assertEquals(UiOperationState.PASS, stateOf(operationId),
                "죽은 participant 를 제외하면 남은 전원의 applied proof 로 barrier 가 열려야 합니다.");
        final UiOperationView view = ledgerPort.readOperation(operationId).orElseThrow();
        assertFalse(view.participants().contains(deadSession), "제외된 session 은 고정 집합에서 빠져야 합니다.");
    }

    @Test
    @DisplayName("§23.4 — 관찰 deadline 이 지나도 operation 은 취소·삭제되지 않고 늦은 proof 로 수렴한다")
    void observationDeadlineDoesNotTerminateTheOperation() {
        final UiOperationExecutor executor = newExecutor(newSession());
        final UiOperationAcceptance accepted = accept("KEY-" + UUID.randomUUID());
        final String operationId = accepted.operationId();
        final long revision = revisionOf(operationId);
        final String digest = digestOf(operationId);

        executor.advance(operationId, System.currentTimeMillis());

        // 관찰 deadline 을 0 에 가깝게 좁혀도 조회는 terminal 을 만들지 않는다.
        properties.setObservationDeadlineMs(1L);
        final UiOperationQueryService queryService =
                new UiOperationQueryService(ledgerPort, AsyncResultStorePort.noop(), properties);
        final var stale = queryService.find(operationId, System.currentTimeMillis() + 60_000L).orElseThrow();
        assertEquals(UiOperationState.PENDING, stale.status());
        assertEquals(UiOperationPhase.RECONCILING, stale.phase(), "지연은 phase 로만 표현돼야 합니다.");

        // 늦게 도착한 proof 로 정상 수렴한다.
        for (final String session : businessSessions) {
            ackPrepared(operationId, session, revision, digest);
        }
        executor.advance(operationId, System.currentTimeMillis());
        for (final String session : businessSessions) {
            ackApplied(operationId, session, revision, digest);
        }
        executor.advance(operationId, System.currentTimeMillis());

        assertEquals(UiOperationState.PASS, stateOf(operationId));
    }

    @Test
    @DisplayName("§13.2 — 같은 key+digest 재요청은 같은 operationId, 다른 digest 는 409 다")
    void idempotencyKeyContract() {
        final String key = "KEY-" + UUID.randomUUID();
        final UiOperationAcceptance first = accept(key);
        final UiOperationAcceptance replay = accept(key);

        assertEquals(first.operationId(), replay.operationId());
        assertFalse(replay.created(), "같은 요청 재전송은 새 operation 을 만들지 않아야 합니다.");

        final var conflict = org.junit.jupiter.api.Assertions.assertThrows(
                com.nori.tc.ui.core.coordination.UiOperationConflictException.class,
                () -> acceptanceService.accept(request(key, 99L), System.currentTimeMillis()));
        assertEquals(com.nori.tc.ui.core.coordination.UiOperationConflictException.IDEMPOTENCY_CONFLICT,
                conflict.errorCode());
    }

    // ---------------------------------------------------------------------
    // fixture
    // ---------------------------------------------------------------------

    private UiOperationAcceptance accept(final String idempotencyKey) {
        return acceptanceService.accept(request(idempotencyKey, 1_001L), System.currentTimeMillis());
    }

    private UiOperationRequest request(final String idempotencyKey, final long modelVersionKey) {
        final String resourceKey = UiResourceKeyPlanner.RESOURCE_TYPE_EQUIPMENT + ":" + eqpId;
        final UiRuntimeDescriptor descriptor = new UiRuntimeDescriptor(
                eqpId, false, 10L, modelVersionKey, 0, 20L, "flow.jar", null,
                null, null, true, UiRuntimeDescriptor.GATEWAY_STATE_STARTED, List.of());
        return new UiOperationRequest(
                idempotencyKey,
                "EQP_MODEL_UPDATE",
                eqpId,
                "tester",
                null,
                List.of(resourceKey),
                Map.of(resourceKey, descriptor),
                Map.of(resourceKey, 0L),
                null,
                null,
                false,
                "SOCKET",
                Map.of(),
                null,
                null);
    }

    private UiOperationExecutor newExecutor(final UiCoordinationSession session) {
        return new UiOperationExecutor(
                session,
                properties,
                ledgerPort,
                resourceHeadPort,
                new RedisUiExecutorLeaseAdapter(context.leaseStore()),
                membershipPort,
                commandPublisher,
                dbFinalizer,
                UiFinalizedRevisionProvider.absent(),
                new UiOperationManifestReader(new ObjectMapper()),
                new UiCompensationIssuer(
                        ledgerPort, resourceHeadPort, manifestFactory,
                        new UiOperationManifestReader(new ObjectMapper())),
                new UiCoordinationMetrics(new SimpleMeterRegistry()));
    }

    private UiCoordinationSession newSession() {
        final UiCoordinationSession session = new UiCoordinationSession(
                "tc-ui-backend", UUID.randomUUID().toString(), UUID.randomUUID().toString(), "pod");
        session.transitionTo(UiMembershipState.ACTIVE_SYNCED);
        return session;
    }

    private String registerBusinessSession() {
        final String sessionId = businessService + ":" + UUID.randomUUID() + ":" + UUID.randomUUID();
        assertEquals(UiCoordinationMembershipPort.Result.OK, membershipPort.register(
                sessionId, "pod", PROTOCOL_VERSION, UiMembershipState.ACTIVE_SYNCED,
                60_000L, System.currentTimeMillis()));
        return sessionId;
    }

    /**
     * heartbeat TTL 만료를 재현합니다 — member key 만 지우고 index 는 남깁니다.
     *
     * <p>index 가 남아야 "TTL 이 한 번 만료된 session 은 같은 sessionId 로 되살아날 수 없다"(§9.1)가
     * 성립하며, {@code excludeExpiredParticipant} 도 member key 부재를 만료 증거로 씁니다.</p>
     */
    private void expireMember(final String sessionId) {
        final org.springframework.data.redis.core.StringRedisTemplate template =
                new org.springframework.data.redis.core.StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        template.delete(CoordinationKeys.memberKey(businessService, sessionId));
        assertTrue(membershipPort.expired(businessService, sessionId));
    }

    private void ackPrepared(final String operationId, final String sessionId, final long revision,
            final String digest) {
        assertEquals("OK", ledgerPort.ackProof(operationId, sessionId, false, revision, digest,
                null, null, "{}", System.currentTimeMillis()));
    }

    private void ackApplied(final String operationId, final String sessionId, final long revision,
            final String digest) {
        assertEquals("OK", ledgerPort.ackProof(operationId, sessionId, true, revision, digest,
                null, null, "{}", System.currentTimeMillis()));
    }

    private long revisionOf(final String operationId) {
        return ledgerPort.readOperation(operationId).orElseThrow().desiredRevision();
    }

    private String digestOf(final String operationId) {
        return ledgerPort.readOperation(operationId).orElseThrow().manifestSha256();
    }

    private UiOperationPhase phaseOf(final String operationId) {
        return ledgerPort.readOperation(operationId).orElseThrow().phase();
    }

    private UiOperationState stateOf(final String operationId) {
        return ledgerPort.readOperation(operationId).orElseThrow().state();
    }

    private long headCommittedRevision() {
        return resourceHeadPort.readHead(UiResourceKeyPlanner.RESOURCE_TYPE_EQUIPMENT, eqpId).committedRevision();
    }

    private long headOpenRevision() {
        return resourceHeadPort.readHead(UiResourceKeyPlanner.RESOURCE_TYPE_EQUIPMENT, eqpId).openRevision();
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /** 발행된 단계별 명령 수만 기록하는 publisher 입니다. */
    private static final class RecordingCommandPublisher
            implements com.nori.tc.ui.core.coordination.port.UiOperationCommandPublisher {

        private int preparePublished;
        private int applyPublished;

        @Override
        public void publishPrepare(final String operationId,
                final com.nori.tc.ui.core.coordination.model.UiOperationManifestView manifest,
                final long desiredRevision, final String manifestSha256, final String deadlineAt) {
            preparePublished++;
        }

        @Override
        public void publishApply(final String operationId,
                final com.nori.tc.ui.core.coordination.model.UiOperationManifestView manifest,
                final long desiredRevision, final String manifestSha256, final String deadlineAt) {
            applyPublished++;
        }
    }

    /** DB finalization 호출 횟수를 기록하는 포트입니다. */
    private static final class RecordingDbFinalizer
            implements com.nori.tc.ui.core.coordination.port.UiOperationDbFinalizationPort {

        private int calls;

        @Override
        public Outcome finalizeToTarget(final String operationId,
                final com.nori.tc.ui.core.coordination.model.UiOperationManifestView manifest,
                final long desiredRevision) {
            calls++;
            return Outcome.FINALIZED;
        }
    }

}
