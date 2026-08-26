package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationAcceptance;
import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
import com.nori.tc.ui.core.coordination.model.UiOperationPhase;
import com.nori.tc.ui.core.coordination.model.UiOperationRequest;
import com.nori.tc.ui.core.coordination.model.UiOperationState;
import com.nori.tc.ui.core.coordination.model.UiOperationView;
import com.nori.tc.ui.core.coordination.model.UiResourceHeadView;
import com.nori.tc.ui.core.coordination.model.UiRuntimeDescriptor;
import com.nori.tc.ui.core.coordination.port.UiCoordinationDigestPort;
import com.nori.tc.ui.core.coordination.port.UiOperationLedgerPort;
import com.nori.tc.ui.core.coordination.port.UiResourceHeadPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §13.2/§18.1 접수 계약을 Docker 없이 고정합니다.
 *
 * <p>특히 <b>candidate 발급이 실패하면 방금 만든 operation 을 남기지 않는다</b>는 것을 봅니다 —
 * 남기면 그 idempotency key 는 영원히 비terminal operation 을 가리켜 같은 요청을 다시 보낼 수 없습니다.</p>
 */
class UiOperationAcceptanceContractTest {

    private static final String RESOURCE_KEY = "equipment:EQP001";

    @Test
    @DisplayName("정상 접수는 202 계약대로 PENDING/ACCEPTED 를 돌려준다")
    void normalAcceptanceReturnsPendingAccepted() {
        final RecordingLedger ledger = new RecordingLedger(UiOperationLedgerPort.CreateResult.CREATED);
        final UiOperationAcceptance acceptance = service(ledger, UiResourceHeadPort.IssueCode.OK)
                .accept(request("KEY-1"), 1_000L);

        assertTrue(acceptance.created());
        assertEquals(UiOperationState.PENDING, acceptance.state());
        assertEquals(UiOperationPhase.ACCEPTED, acceptance.phase());
        assertTrue(ledger.aborts.isEmpty());
    }

    @Test
    @DisplayName("같은 key 다른 digest 는 409 IDEMPOTENCY_CONFLICT 다")
    void conflictingDigestIsRejected() {
        final RecordingLedger ledger =
                new RecordingLedger(UiOperationLedgerPort.CreateResult.CONFLICT_DIGEST);

        final UiOperationConflictException conflict = assertThrows(UiOperationConflictException.class,
                () -> service(ledger, UiResourceHeadPort.IssueCode.OK).accept(request("KEY-1"), 1_000L));

        assertEquals(UiOperationConflictException.IDEMPOTENCY_CONFLICT, conflict.errorCode());
        assertTrue(ledger.aborts.isEmpty(), "충돌은 기존 operation 을 건드리지 않아야 합니다.");
    }

    @Test
    @DisplayName("같은 key 같은 digest 재요청은 새 operation 을 만들지 않는다")
    void replayReturnsExistingOperation() {
        final RecordingLedger ledger = new RecordingLedger(UiOperationLedgerPort.CreateResult.EXISTING);

        final UiOperationAcceptance acceptance =
                service(ledger, UiResourceHeadPort.IssueCode.OK).accept(request("KEY-1"), 1_000L);

        assertFalse(acceptance.created());
        assertEquals(0, ledger.issueCalls, "재요청이 candidate 를 다시 발급하면 revision 이 낭비됩니다.");
    }

    @Test
    @DisplayName("리소스가 다른 operation 에 잡혀 있으면 409 RESOURCE_BUSY 이고 접수는 정리된다")
    void resourceBusyIsRejectedAndAccepted0perationIsAborted() {
        final RecordingLedger ledger = new RecordingLedger(UiOperationLedgerPort.CreateResult.CREATED);

        final UiOperationConflictException conflict = assertThrows(UiOperationConflictException.class,
                () -> service(ledger, UiResourceHeadPort.IssueCode.RESOURCE_BUSY).accept(request("KEY-1"), 1_000L));

        assertEquals(UiOperationConflictException.RESOURCE_BUSY, conflict.errorCode());
        assertEquals(List.of("RESOURCE_BUSY"), ledger.aborts,
                "발급 실패한 operation 을 남기면 그 key 로는 다시 시도할 수 없습니다.");
    }

    @Test
    @DisplayName("미종결 operation 이 차단 중이면 409 RESOURCE_BLOCKED 다")
    void resourceBlockedIsRejected() {
        final RecordingLedger ledger = new RecordingLedger(UiOperationLedgerPort.CreateResult.CREATED);

        final UiOperationConflictException conflict = assertThrows(UiOperationConflictException.class,
                () -> service(ledger, UiResourceHeadPort.IssueCode.RESOURCE_BLOCKED)
                        .accept(request("KEY-1"), 1_000L));

        assertEquals(UiOperationConflictException.RESOURCE_BLOCKED, conflict.errorCode());
    }

    @Test
    @DisplayName("같은 idempotency key 는 항상 같은 operationId 로 매핑된다")
    void operationIdIsDeterministicPerIdempotencyKey() {
        final UiOperationManifestFactory factory = manifestFactory();
        assertEquals(factory.operationIdOf("KEY-1"), factory.operationIdOf("KEY-1"));
        assertFalse(factory.operationIdOf("KEY-1").equals(factory.operationIdOf("KEY-2")));
    }

    @Test
    @DisplayName("manifest 는 왕복해도 실행에 필요한 필드를 잃지 않는다")
    void manifestRoundTripsForExecution() {
        final UiOperationManifestFactory factory = manifestFactory();
        final UiOperationManifestFactory.Manifest manifest = factory.build(request("KEY-1"));

        final UiOperationManifestView view =
                new UiOperationManifestReader(new ObjectMapper()).read(manifest.canonicalJson());

        assertEquals("EQP_UPDATE", view.eventType());
        assertEquals("EQP001", view.eqpId());
        assertEquals(List.of(RESOURCE_KEY), view.resourceKeys());
        assertEquals(0L, view.expectedBaseRevisions().get(RESOURCE_KEY));
        assertTrue(view.descriptorJsonByResourceKey().get(RESOURCE_KEY).contains("EQP001"));
        assertEquals("SOCKET", view.interfaceType());
    }

    private UiOperationAcceptanceService service(
            final RecordingLedger ledger,
            final UiResourceHeadPort.IssueCode issueCode
    ) {
        return new UiOperationAcceptanceService(ledger, resourceHeadPort(issueCode, ledger), manifestFactory());
    }

    private static UiOperationManifestFactory manifestFactory() {
        final ObjectMapper objectMapper = new ObjectMapper();
        return new UiOperationManifestFactory(
                objectMapper, new UiRuntimeDescriptorCodec(objectMapper), new Sha256DigestPort());
    }

    private static UiOperationRequest request(final String idempotencyKey) {
        final UiRuntimeDescriptor descriptor = new UiRuntimeDescriptor(
                "EQP001", false, 10L, 1_001L, 0, null, null, null,
                null, null, true, null, null);
        return new UiOperationRequest(
                idempotencyKey, "EQP_UPDATE", "EQP001", "tester", null,
                List.of(RESOURCE_KEY), Map.of(RESOURCE_KEY, descriptor), Map.of(RESOURCE_KEY, 0L),
                null, null, false, "SOCKET", Map.of(), null, null);
    }

    private static UiResourceHeadPort resourceHeadPort(
            final UiResourceHeadPort.IssueCode issueCode,
            final RecordingLedger ledger
    ) {
        return new UiResourceHeadPort() {
            @Override
            public IssueResult issueCandidate(
                    final String operationId, final List<Candidate> candidates, final long nowEpochMs) {
                ledger.issueCalls++;
                return new IssueResult(issueCode, issueCode == IssueCode.OK ? 1L : null, "other-op");
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

    /** 공통 모듈에 의존하지 않고 §8.4 digest 형식만 맞추는 테스트 대역입니다. */
    private static final class Sha256DigestPort implements UiCoordinationDigestPort {
        @Override
        public String canonicalize(final String json) {
            return json;
        }

        @Override
        public String sha256Hex(final String canonicalJson) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    /** 생성 결과 코드를 고정하고 abort 호출을 기록하는 원장입니다. */
    private static final class RecordingLedger implements UiOperationLedgerPort {

        private final String createCode;
        private final List<String> aborts = new ArrayList<>();
        private int issueCalls;

        private RecordingLedger(final String createCode) {
            this.createCode = createCode;
        }

        @Override
        public CreateResult createOperation(final String operationId, final String manifestSha256,
                final String manifestJson, final Map<String, String> extraFields, final long nowEpochMs) {
            return new CreateResult(createCode, UiOperationState.PENDING, UiOperationPhase.ACCEPTED, null, null);
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
            return "OK";
        }

        @Override
        public String commitHead(final String operationId, final List<String> resourceHeadKeys,
                final List<Long> expectedBaseRevisions, final long nowEpochMs) {
            return "OK";
        }

        @Override
        public int checkApplied(
                final String operationId, final boolean advanceToFinalizing, final long nowEpochMs) {
            return 0;
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
            return List.of();
        }
    }
}
