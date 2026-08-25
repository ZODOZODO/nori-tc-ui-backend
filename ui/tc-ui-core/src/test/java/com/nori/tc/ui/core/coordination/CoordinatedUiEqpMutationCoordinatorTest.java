package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationAcceptance;
import com.nori.tc.ui.core.coordination.model.UiOperationPhase;
import com.nori.tc.ui.core.coordination.model.UiOperationState;
import com.nori.tc.ui.core.coordination.model.UiOperationView;
import com.nori.tc.ui.core.coordination.model.UiResourceHeadView;
import com.nori.tc.ui.core.coordination.port.UiCoordinationDigestPort;
import com.nori.tc.ui.core.coordination.port.UiExecutorLeasePort;
import com.nori.tc.ui.core.coordination.port.UiFinalizedRevisionProvider;
import com.nori.tc.ui.core.coordination.port.UiOperationCommandPublisher;
import com.nori.tc.ui.core.coordination.port.UiOperationDbFinalizationPort;
import com.nori.tc.ui.core.coordination.port.UiOperationLedgerPort;
import com.nori.tc.ui.core.coordination.port.UiResourceHeadPort;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.port.db.EqpManageQueryPort;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.core.coordination.model.UiMembershipState;
import com.nori.tc.ui.core.coordination.port.UiCoordinationMembershipPort;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpParam;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 조정 구성의 mutation 접수 경로를 고정합니다(§13.2/§11.2/§23.2).
 *
 * <p>특히 <b>Gateway participant 를 언제 요구하는가</b>를 봅니다 — non-PLC model 변경에까지 Gateway
 * proof 를 요구하면 할 일이 없는 Gateway 의 응답을 영원히 기다리고(§23.2 2단락 위반), 반대로 route
 * partition 이 바뀌는데 요구하지 않으면 Gateway 가 옛 배치로 계속 처리합니다.</p>
 */
class CoordinatedUiEqpMutationCoordinatorTest {

    private static final String EQP_ID = "EQP001";

    @Test
    @DisplayName("설비 생성은 Gateway 참여를 요구하고 tombstone 이 아닌 descriptor 를 만든다")
    void createRequiresGatewayParticipant() {
        final RecordingLedger ledger = new RecordingLedger();
        final UiOperationAcceptance acceptance =
                coordinator(ledger, List.of()).create(createCommand(), "KEY-1");

        assertTrue(acceptance.created());
        assertEquals(UiOperationState.PENDING, acceptance.state());
        assertEquals(UiOperationPhase.ACCEPTED, acceptance.phase());
        assertTrue(ledger.lastManifest.contains("\"requiresGatewayParticipant\":true"),
                "새 설비는 Gateway 가 컨텍스트를 만들어야 하므로 participant 로 참여해야 합니다.");
        assertTrue(ledger.lastManifest.contains("\"dbCommandKind\":\"CREATE\""));
    }

    @Test
    @DisplayName("model 만 바뀌는 수정은 Gateway 참여를 요구하지 않는다")
    void modelOnlyUpdateDoesNotRequireGatewayParticipant() {
        final RecordingLedger ledger = new RecordingLedger();
        coordinator(ledger, List.of()).update(EQP_ID, updateCommand(2_002L, 0), "KEY-1");

        assertTrue(ledger.lastManifest.contains("\"requiresGatewayParticipant\":false"),
                "§23.2 — non-PLC model 변경은 Gateway proof 를 요구하지 않아야 합니다.");
        assertTrue(ledger.lastManifest.contains("\"eventType\":\"EQP_MODEL_UPDATE\""));
    }

    @Test
    @DisplayName("route partition 이 바뀌면 Gateway 참여를 요구한다")
    void routePartitionChangeRequiresGatewayParticipant() {
        final RecordingLedger ledger = new RecordingLedger();
        coordinator(ledger, List.of()).update(EQP_ID, updateCommand(1_001L, 3), "KEY-1");

        assertTrue(ledger.lastManifest.contains("\"requiresGatewayParticipant\":true"));
        assertTrue(ledger.lastManifest.contains("\"routePartition\":3"));
    }

    @Test
    @DisplayName("model 공유 설비가 있으면 영향 집합이 정렬된 형태로 확장된다")
    void sharedModelEquipmentExpandsTheResourceSet() {
        final RecordingLedger ledger = new RecordingLedger();
        coordinator(ledger, List.of("EQP003", "EQP002")).update(EQP_ID, updateCommand(2_002L, 0), "KEY-1");

        assertTrue(ledger.lastManifest.contains(
                        "[\"equipment:EQP001\",\"equipment:EQP002\",\"equipment:EQP003\"]"),
                "영향 집합은 정렬돼야 하고 공유 설비를 모두 포함해야 합니다. manifest=" + ledger.lastManifest);
    }

    @Test
    @DisplayName("삭제는 tombstone descriptor 와 DELETE 명령을 고정한다")
    void deleteProducesTombstone() {
        final RecordingLedger ledger = new RecordingLedger();
        coordinator(ledger, List.of()).delete(EQP_ID, "KEY-1");

        assertTrue(ledger.lastManifest.contains("\"deleted\":true"));
        assertTrue(ledger.lastManifest.contains("\"gatewayDesiredState\":\"ENDED\""));
        assertTrue(ledger.lastManifest.contains("\"dbCommandKind\":\"DELETE\""));
    }

    @Test
    @DisplayName("없는 설비의 수정/삭제는 접수 전에 404 로 거부한다")
    void missingEquipmentIsRejectedBeforeAcceptance() {
        final RecordingLedger ledger = new RecordingLedger();
        final EqpManageQueryPort emptyPort = mock(EqpManageQueryPort.class);
        when(emptyPort.findManageSnapshotByEqpId(anyString())).thenReturn(Optional.empty());

        final CoordinatedUiEqpMutationCoordinator coordinator = coordinator(ledger, List.of(), emptyPort);

        assertThrows(UiNotFoundException.class, () -> coordinator.delete(EQP_ID, "KEY-1"));
        assertFalse(coordinator.appliesImmediately());
        assertTrue(ledger.creates.isEmpty(), "존재하지 않는 설비로 operation 을 만들면 안 됩니다.");
    }

    // ------------------------------------------------------------------ fixture

    private CoordinatedUiEqpMutationCoordinator coordinator(
            final RecordingLedger ledger,
            final List<String> sharedEqpIds
    ) {
        final EqpManageQueryPort queryPort = mock(EqpManageQueryPort.class);
        when(queryPort.findManageSnapshotByEqpId(anyString())).thenReturn(Optional.of(snapshot()));
        return coordinator(ledger, sharedEqpIds, queryPort);
    }

    private CoordinatedUiEqpMutationCoordinator coordinator(
            final RecordingLedger ledger,
            final List<String> sharedEqpIds,
            final EqpManageQueryPort queryPort
    ) {
        final ObjectMapper objectMapper = new ObjectMapper();
        final UiOperationManifestFactory manifestFactory = new UiOperationManifestFactory(
                objectMapper, new UiRuntimeDescriptorCodec(objectMapper), new Sha256DigestPort());
        final UiResourceHeadPort headPort = headPort();
        final UiCoordinationProperties properties = new UiCoordinationProperties();
        final UiCoordinationMetrics metrics = new UiCoordinationMetrics(new SimpleMeterRegistry());
        final UiCoordinationSession fenced =
                new UiCoordinationSession("tc-ui-backend", "pod", "boot", "pod");
        fenced.fence();
        final UiOperationExecutor executor = new UiOperationExecutor(
                fenced, properties, ledger, headPort, leasePort(), membership(), publisher(),
                (operationId, manifest, revision) -> UiOperationDbFinalizationPort.Outcome.FINALIZED,
                UiFinalizedRevisionProvider.absent(),
                new UiOperationManifestReader(objectMapper), metrics);

        return new CoordinatedUiEqpMutationCoordinator(
                queryPort,
                new UiResourceKeyPlanner(eqpQueryPort(sharedEqpIds)),
                new UiEqpDescriptorFactory(),
                headPort,
                new UiOperationAcceptanceService(ledger, headPort, manifestFactory),
                new UiOperationReconciler(ledger, executor, properties, metrics),
                objectMapper);
    }

    private static EqpManagementCommand.Create createCommand() {
        return new EqpManagementCommand.Create(
                "tester", EQP_ID, 1L, UiProtocolType.SOCKET, "PASSIVE", false, 0,
                "10.0.0.1", 6000, 1_001L, "V1", 30L, 20L, null, null, null, null);
    }

    private static EqpManagementCommand.Update updateCommand(
            final long modelVersionKey,
            final int routePartition
    ) {
        return new EqpManagementCommand.Update(
                "tester", 1L, "PASSIVE", false, routePartition, "10.0.0.1", 6000,
                modelVersionKey, "V1", 30L, 20L, null, null, null, null);
    }

    private static EqpManagementSnapshot snapshot() {
        final UiEqp eqp = new UiEqp(
                1L, EQP_ID, 1L, UiProtocolType.SOCKET, "PASSIVE", false, 0,
                "10.0.0.1", 6000, 1_001L, "V1", 30L, 20L, true,
                OffsetDateTime.now(), OffsetDateTime.now(), "tester", "tester");
        return new EqpManagementSnapshot(
                eqp, null, null, null, null, null, null, null, List.of(), List.of(), List.of(), null, null);
    }

    private static UiResourceHeadPort headPort() {
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

    private static UiExecutorLeasePort leasePort() {
        return new UiExecutorLeasePort() {
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

    private static UiCoordinationMembershipPort membership() {
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
                return List.of();
            }

            @Override
            public boolean expired(final String serviceName, final String sessionId) {
                return false;
            }
        };
    }

    private static UiOperationCommandPublisher publisher() {
        return new UiOperationCommandPublisher() {
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

    private static EqpQueryPort eqpQueryPort(final List<String> sharedEqpIds) {
        return new EqpQueryPort() {
            @Override
            public PagedResponse<UiEqp> findAll(final UiPageRequest pageRequest) {
                return PagedResponse.empty(0, 1);
            }

            @Override
            public Optional<UiEqp> findByEqpId(final String eqpId) {
                return Optional.empty();
            }

            @Override
            public List<String> findParamVersionsByEqpId(final String eqpId) {
                return List.of();
            }

            @Override
            public List<UiEqpParam> findParamsByEqpIdAndVersion(final String eqpId, final String version) {
                return List.of();
            }

            @Override
            public Optional<EqpCheckoutStateView> findCheckoutStateByEqpId(final String eqpId) {
                return Optional.empty();
            }

            @Override
            public Optional<EqpRuntimeStateView> findRuntimeStateByEqpId(final String eqpId) {
                return Optional.empty();
            }

            @Override
            public List<String> findEqpIdsByModelVersionKey(final long modelVersionKey) {
                return sharedEqpIds;
            }
        };
    }

    /** §8.4 형식만 맞추는 digest 대역입니다. */
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

    /** 생성 요청의 manifest 를 붙잡아 두는 원장입니다. */
    private static final class RecordingLedger implements UiOperationLedgerPort {

        private final List<String> creates = new ArrayList<>();
        private String lastManifest = "";

        @Override
        public CreateResult createOperation(final String operationId, final String manifestSha256,
                final String manifestJson, final Map<String, String> extraFields, final long nowEpochMs) {
            creates.add(operationId);
            lastManifest = manifestJson;
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
        public Optional<String> readManifest(final String operationId) {
            return Optional.empty();
        }

        @Override
        public List<String> pendingOperationIds(final long limit) {
            return List.of();
        }
    }
}
