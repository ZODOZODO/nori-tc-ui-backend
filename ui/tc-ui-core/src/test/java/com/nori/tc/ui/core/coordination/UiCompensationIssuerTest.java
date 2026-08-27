package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationPhase;
import com.nori.tc.ui.core.coordination.model.UiOperationState;
import com.nori.tc.ui.core.coordination.model.UiOperationView;
import com.nori.tc.ui.core.coordination.model.UiResourceHeadView;
import com.nori.tc.ui.core.coordination.port.UiOperationLedgerPort;
import com.nori.tc.ui.core.coordination.port.UiResourceHeadPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §13.8 보상 operation 발급을 고정합니다.
 *
 * <p>보상은 "무엇을 되돌리는가"(compensatesRevision)와 "어떤 상태로 되돌리는가"(이전 descriptor)를
 * 둘 다 정확히 실어야 합니다. 하나라도 어긋나면 되돌린 결과가 원 상태가 아니게 됩니다.</p>
 */
class UiCompensationIssuerTest {

    private static final String FAILED_ID = "op-failed";
    private static final long COMMITTED_REVISION = 7L;

    @Test
    @DisplayName("보상 manifest 는 목표와 이전 상태를 맞바꾸고 DB 명령을 싣지 않는다")
    void compensationManifestSwapsTargetAndPrevious() {
        final RecordingLedger ledger = new RecordingLedger(originalManifest());
        final String compensationId = issuer(ledger).issueFor(failedOperation(null), 100L);

        assertNotNull(compensationId);
        final JsonNode manifest = new ObjectMapper().readTree(ledger.lastManifestJson);

        assertEquals("model-1", manifest.path("descriptors").path("equipment:EQP001")
                .path("modelVersionKey").asString(), "되돌릴 목표는 원 operation 의 이전 상태다");
        assertEquals("model-2", manifest.path("previousDescriptors").path("equipment:EQP001")
                .path("modelVersionKey").asString(), "지금 런타임에 올라간 것은 원 operation 의 목표 상태다");
        assertEquals(COMMITTED_REVISION, manifest.path("compensatesRevision").asLong());
        assertEquals(COMMITTED_REVISION, manifest.path("expectedBaseRevisions")
                .path("equipment:EQP001").asLong());
        /*
         * 보상 대상 실패는 DB 확정 이전 단계에서만 발생한다. 역명령을 지어내면 확인되지 않은 상태를
         * 덮어쓰게 되므로 아예 싣지 않는다.
         */
        assertTrue(manifest.path("dbCommandKind").isMissingNode());
        assertTrue(manifest.path("dbCommand").isMissingNode());

        assertEquals(COMMITTED_REVISION, ledger.lastExtraFields.get("compensatesRevision") == null
                ? -1L : Long.parseLong(ledger.lastExtraFields.get("compensatesRevision")),
                "원장 hash 에도 실려야 공통 원장이 claim 인계 조건을 검증할 수 있다");
    }

    @Test
    @DisplayName("보상의 보상은 발급하지 않는다")
    void nestedCompensationIsRefused() {
        final RecordingLedger ledger = new RecordingLedger(originalManifest());

        assertNull(issuer(ledger).issueFor(failedOperation(3L), 100L));
        assertTrue(ledger.created.isEmpty(),
                "되돌리기가 또 실패하면 어느 상태가 올라가 있는지 확정할 수 없다 — 자동 재시도는 불확실을 키운다.");
    }

    @Test
    @DisplayName("이전 상태 descriptor 가 영향 리소스를 전부 덮지 않으면 발급하지 않는다")
    void partialPreviousDescriptorsBlockIssuance() {
        // 공유 설비 EQP002 의 이전 상태가 없다 — 절반만 되돌리면 §11.1 이 금지하는 분열 상태가 된다.
        final RecordingLedger ledger = new RecordingLedger(originalManifest());
        final UiOperationView failed = new UiOperationView(
                FAILED_ID, null, "EQP001", "EQP_MODEL_UPDATE",
                UiOperationState.REPAIR_REQUIRED, UiOperationPhase.COMPENSATING,
                List.of("equipment:EQP001", "equipment:EQP002"), List.of(),
                "digest", 0L, COMMITTED_REVISION, false, null, null,
                "REPAIR_REQUIRED", "커밋 후 실패", "LAST_PARTICIPANT_EXPIRED", 1L, 2L, 3L);

        assertNull(issuer(ledger).issueFor(failed, 100L));
        assertTrue(ledger.created.isEmpty());
    }

    @Test
    @DisplayName("§16.1 — 설비 생성의 보상은 임시 행을 지우는 DELETE 를 싣는다")
    void createCompensationCarriesDeleteCommand() {
        final RecordingLedger ledger = new RecordingLedger(createManifest());

        final String compensationId = issuer(ledger).issueFor(failedOperation(null), 100L);

        assertNotNull(compensationId);
        final JsonNode manifest = new ObjectMapper().readTree(ledger.lastManifestJson);
        assertTrue(manifest.path("descriptors").path("equipment:EQP001").path("deleted").asBoolean(false),
                "생성의 이전 상태는 tombstone(존재하지 않음)이다");
        /*
         * 생성 operation 은 §16.1 에 따라 PREPARE 전에 임시 행을 만든다. 보상이 그 행을 지우지 않으면
         * 아무도 쓰지 못하는 설비 행이 남고, 같은 eqpId 재요청은 중복 등록으로 거부된다.
         */
        assertEquals("DELETE", manifest.path("dbCommandKind").asString());
        assertTrue(manifest.path("dbCommand").isObject());
    }

    // ------------------------------------------------------------------ fixture

    /** 설비 생성 operation 의 원 manifest 입니다 — 이전 상태가 tombstone 입니다. */
    private static String createManifest() {
        return """
                {"schemaVersion":2,"eventType":"EQP_CREATE","eqpId":"EQP001",\
                "requiresGatewayParticipant":true,"resourceKeys":["equipment:EQP001"],\
                "descriptors":{"equipment:EQP001":{"eqpId":"EQP001","modelVersionKey":"model-2"}},\
                "expectedBaseRevisions":{"equipment:EQP001":6},\
                "previousDescriptors":{"equipment:EQP001":{"eqpId":"EQP001","deleted":true}},\
                "dbCommandKind":"CREATE","dbCommand":{"actor":"tester"}}""";
    }

    private static UiCompensationIssuer issuer(final RecordingLedger ledger) {
        return CompensationIssuerFixture.issuer(ledger, headPort());
    }

    private static UiOperationView failedOperation(final Long compensatesRevision) {
        return new UiOperationView(
                FAILED_ID, null, "EQP001", "EQP_MODEL_UPDATE",
                UiOperationState.REPAIR_REQUIRED, UiOperationPhase.COMPENSATING,
                List.of("equipment:EQP001"), List.of(),
                "digest", 0L, COMMITTED_REVISION, false, compensatesRevision, null,
                "REPAIR_REQUIRED", "커밋 후 실패", "LAST_PARTICIPANT_EXPIRED", 1L, 2L, 3L);
    }

    private static String originalManifest() {
        return """
                {"schemaVersion":2,"eventType":"EQP_MODEL_UPDATE","eqpId":"EQP001",\
                "requiresGatewayParticipant":false,"resourceKeys":["equipment:EQP001"],\
                "descriptors":{"equipment:EQP001":{"eqpId":"EQP001","modelVersionKey":"model-2"}},\
                "expectedBaseRevisions":{"equipment:EQP001":6},\
                "previousDescriptors":{"equipment:EQP001":{"eqpId":"EQP001","modelVersionKey":"model-1"}},\
                "dbCommandKind":"UPDATE","dbCommand":{"actor":"tester"}}""";
    }

    private static UiResourceHeadPort headPort() {
        return new UiResourceHeadPort() {
            @Override
            public IssueResult issueCandidate(
                    final String operationId, final List<Candidate> candidates, final long nowEpochMs) {
                throw new UnsupportedOperationException("보상은 일반 발급 경로를 쓰지 않는다");
            }

            @Override
            public IssueResult issueCompensationCandidate(
                    final String operationId, final String compensatesOperationId,
                    final List<Candidate> candidates, final long nowEpochMs) {
                return new IssueResult(IssueCode.OK, COMMITTED_REVISION + 1, null);
            }

            @Override
            public UiResourceHeadView readHead(final String resourceType, final String resourceId) {
                return new UiResourceHeadView(
                        resourceType + ":" + resourceId, COMMITTED_REVISION, 0L, FAILED_ID, FAILED_ID);
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

    /** 보상 생성 호출을 붙잡아 두는 원장입니다. */
    private static final class RecordingLedger implements UiOperationLedgerPort {

        private final String manifestJson;
        private final List<String> created = new ArrayList<>();
        private String lastManifestJson;
        private Map<String, String> lastExtraFields = Map.of();

        private RecordingLedger(final String manifestJson) {
            this.manifestJson = manifestJson;
        }

        @Override
        public CreateResult createOperation(
                final String operationId, final String manifestSha256, final String canonicalJson,
                final Map<String, String> extraFields, final long nowEpochMs) {
            created.add(operationId);
            lastManifestJson = canonicalJson;
            lastExtraFields = extraFields;
            return new CreateResult(CreateResult.CREATED, null, null, null, null);
        }

        @Override
        public Optional<String> readManifest(final String operationId) {
            return Optional.of(manifestJson);
        }

        @Override
        public String transition(
                final String operationId, final Set<UiOperationPhase> expectedPhases,
                final UiOperationPhase newPhase, final UiOperationState newState,
                final String executorSessionId, final Long executorEpoch, final String errorCode,
                final String errorMsg, final String repairReason, final long nowEpochMs) {
            return "OK";
        }

        @Override
        public String freezeParticipants(
                final String operationId, final List<String> participantIds,
                final boolean force, final long nowEpochMs) {
            return "OK";
        }

        @Override
        public String excludeExpiredParticipant(
                final String operationId, final String serviceName,
                final String expiredSessionId, final long nowEpochMs) {
            return "OK";
        }

        @Override
        public String closeBarrier(
                final String operationId, final List<String> resourceHeadKeys, final long nowEpochMs) {
            return "OK";
        }

        @Override
        public String commitHead(
                final String operationId, final List<String> resourceHeadKeys,
                final List<Long> expectedBaseRevisions, final long nowEpochMs) {
            return "OK";
        }

        @Override
        public int checkApplied(
                final String operationId, final boolean advanceToFinalizing, final long nowEpochMs) {
            return 0;
        }

        @Override
        public String recordDbFinalized(
                final String operationId, final String executorSessionId, final long executorEpoch,
                final long finalizedRevision, final long nowEpochMs) {
            return "OK";
        }

        @Override
        public String openBarrierAndPass(
                final String operationId, final List<String> resourceHeadKeys, final long nowEpochMs) {
            return "OK";
        }

        @Override
        public String abort(
                final String operationId, final List<String> resourceHeadKeys, final String errorCode,
                final String errorMsg, final long nowEpochMs) {
            return "OK";
        }
        @Override
        public String markRolledBack(final String operationId, final String compensationOperationId,
                final long nowEpochMs) {
            return "OK";
        }


        @Override
        public String ackProof(
                final String operationId, final String participantId, final boolean applied,
                final long revision, final String digest, final String ownerLeaseKey,
                final Long ownerEpoch, final String proofJson, final long nowEpochMs) {
            return "OK";
        }

        @Override
        public Optional<UiOperationView> readOperation(final String operationId) {
            return Optional.empty();
        }

        @Override
        public List<com.nori.tc.ui.core.coordination.model.UiParticipantProofView> readParticipantProofs(
                final String operationId) {
            return List.of();
        }

        @Override
        public List<com.nori.tc.ui.core.coordination.model.UiPhaseTransitionView> readPhaseHistory(
                final String operationId) {
            return List.of();
        }

        @Override
        public List<String> pendingOperationIds(final long limit) {
            return List.of();
        }
    }
}
