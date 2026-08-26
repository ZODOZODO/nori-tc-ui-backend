package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationAcceptance;
import com.nori.tc.ui.core.coordination.model.UiOperationPhase;
import com.nori.tc.ui.core.coordination.model.UiOperationRequest;
import com.nori.tc.ui.core.coordination.model.UiOperationState;
import com.nori.tc.ui.core.coordination.model.UiOperationView;
import com.nori.tc.ui.core.coordination.model.UiResourceHeadView;
import com.nori.tc.ui.core.coordination.port.UiCoordinationDigestPort;
import com.nori.tc.ui.core.coordination.port.UiOperationLedgerPort;
import com.nori.tc.ui.core.coordination.port.UiResourceHeadPort;
import com.nori.tc.ui.core.deploy.DeployCurrentState;
import com.nori.tc.ui.core.deploy.DeployDesiredState;
import com.nori.tc.ui.core.deploy.DeployExecutionTarget;
import com.nori.tc.ui.core.deploy.DeployResolvedStepPlan;
import com.nori.tc.ui.core.deploy.DeployScenarioAction;
import com.nori.tc.ui.core.deploy.DeployStepStatus;
import com.nori.tc.ui.core.deploy.DeployTargetApp;
import com.nori.tc.ui.core.deploy.DeployTargetPlanDraft;
import com.nori.tc.ui.core.deploy.DeployTargetStatus;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpParam;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * deploy target 의 operation 접수를 고정합니다(§13.6 / Plan D9).
 *
 * <p>핵심은 <b>접수 단위</b>입니다. 모델 공유 상태를 바꾸는 이벤트에서 target 마다 operation 을
 * 만들면, 같은 모델을 쓰는 설비들이 서로의 리소스를 잠근 채 각자 다른 목표 상태를 쓰려 듭니다.
 * 영향 집합이 겹치는 target 은 한 operation 으로 합쳐야 합니다.</p>
 */
class DeployOperationAcceptanceTest {

    private static final long DEPLOY_KEY = 77L;

    @Test
    @DisplayName("폐쇄가 없는 이벤트는 target 마다 별 operation 으로 접수한다")
    void nonSharedEventKeepsOneOperationPerTarget() {
        final RecordingLedger ledger = new RecordingLedger();
        final DeployOperationAcceptanceService service = service(ledger, List.of());

        final List<DeployExecutionTarget> targets = List.of(
                target(11L, "EQP001", 1_001L, 1_001L, 30L, 30L, DeployTargetApp.GATEWAY),
                target(12L, "EQP002", 1_002L, 1_002L, 31L, 31L, DeployTargetApp.GATEWAY));

        final List<DeployOperationGroup> groups =
                service.group(UiCommandEventType.EQP_UPDATE, targets);

        assertEquals(2, groups.size(), "폐쇄가 없으면 target 이 서로를 잠그지 않으므로 합칠 이유가 없습니다.");
        assertEquals(List.of("equipment:EQP001"), groups.get(0).resourceKeys());
        assertEquals(List.of("equipment:EQP002"), groups.get(1).resourceKeys());

        final UiOperationAcceptance acceptance =
                service.accept(DEPLOY_KEY, UiCommandEventType.EQP_UPDATE, groups.get(0), "tester");

        assertTrue(acceptance.created());
        assertEquals(UiOperationState.PENDING, acceptance.state());
        assertEquals(UiOperationPhase.ACCEPTED, acceptance.phase());
        assertTrue(ledger.lastManifest.contains(
                        "\"dbCommandKind\":\"" + UiOperationRequest.DB_COMMAND_DEPLOY_TARGET + "\""),
                "deploy 확정은 설비 row 를 만들거나 지우지 않는 별 명령 종류여야 합니다. manifest="
                        + ledger.lastManifest);
        assertTrue(ledger.lastManifest.contains("\"deployTargetKeys\":[11]"),
                "operation 이 확정할 target 목록이 manifest 에 있어야 합니다. manifest=" + ledger.lastManifest);
    }

    @Test
    @DisplayName("모델을 공유하는 target 들은 하나의 operation 으로 합친다")
    void sharedModelTargetsCollapseIntoOneOperation() {
        final RecordingLedger ledger = new RecordingLedger();
        // 두 target 이 모두 modelVersionKey 1_001 을 쓰고, 그 모델을 EQP003 도 공유한다.
        final DeployOperationAcceptanceService service =
                service(ledger, List.of("EQP001", "EQP002", "EQP003"));

        final List<DeployExecutionTarget> targets = List.of(
                target(11L, "EQP001", 1_001L, 1_001L, 30L, 31L, DeployTargetApp.BUSINESS),
                target(12L, "EQP002", 1_001L, 1_001L, 30L, 31L, DeployTargetApp.BUSINESS));

        final List<DeployOperationGroup> groups =
                service.group(UiCommandEventType.EQP_BUSINESS_JARFILE_UPDATE, targets);

        assertEquals(1, groups.size(),
                "같은 모델을 공유하면 서로의 설비를 잠그므로 한 operation 이어야 합니다.");
        assertEquals(List.of("equipment:EQP001", "equipment:EQP002", "equipment:EQP003"),
                groups.get(0).resourceKeys());
        assertEquals(11L, groups.get(0).representativeTargetKey());

        service.accept(DEPLOY_KEY, UiCommandEventType.EQP_BUSINESS_JARFILE_UPDATE, groups.get(0), "tester");

        assertEquals(List.of(DeployOperationAcceptanceService.executionIdOf(DEPLOY_KEY, 11L)),
                ledger.idempotencyKeys,
                "그룹 key 는 최소 target key 로 결정적으로 도출되어야 합니다.");
        assertTrue(ledger.lastManifest.contains("\"deployTargetKeys\":[11,12]"),
                "manifest=" + ledger.lastManifest);
        assertTrue(ledger.lastManifest.contains("\"requiresGatewayParticipant\":false"),
                "BUSINESS step 만 있으면 Gateway 는 할 일이 없습니다. manifest=" + ledger.lastManifest);
    }

    @Test
    @DisplayName("GATEWAY step 이 하나라도 있으면 Gateway 참여를 요구한다")
    void gatewayStepRequiresGatewayParticipant() {
        final RecordingLedger ledger = new RecordingLedger();
        final DeployOperationAcceptanceService service = service(ledger, List.of());
        final List<DeployOperationGroup> groups = service.group(
                UiCommandEventType.EQP_UPDATE,
                List.of(target(11L, "EQP001", 1_001L, 1_001L, 30L, 30L, DeployTargetApp.GATEWAY)));

        service.accept(DEPLOY_KEY, UiCommandEventType.EQP_UPDATE, groups.get(0), "tester");

        assertTrue(ledger.lastManifest.contains("\"requiresGatewayParticipant\":true"),
                "manifest=" + ledger.lastManifest);
    }

    @Test
    @DisplayName("폐쇄로 끌려온 설비가 있는데 target 목표 상태가 갈리면 접수하지 않는다")
    void conflictingDesiredStateWithClosureOnlyEquipmentIsRejected() {
        final DeployOperationAcceptanceService service =
                service(new RecordingLedger(), List.of("EQP001", "EQP002", "EQP003"));

        // 두 target 이 같은 모델을 공유하면서 서로 다른 JAR 을 목표로 한다.
        final List<DeployExecutionTarget> targets = List.of(
                target(11L, "EQP001", 1_001L, 1_001L, 30L, 31L, DeployTargetApp.BUSINESS),
                target(12L, "EQP002", 1_001L, 1_001L, 30L, 32L, DeployTargetApp.BUSINESS));

        final List<DeployOperationGroup> groups =
                service.group(UiCommandEventType.EQP_BUSINESS_JARFILE_UPDATE, targets);
        assertEquals(1, groups.size());

        final UiConflictException thrown = assertThrows(UiConflictException.class, () ->
                service.accept(DEPLOY_KEY, UiCommandEventType.EQP_BUSINESS_JARFILE_UPDATE,
                        groups.get(0), "tester"));
        assertTrue(thrown.getMessage().contains("EQP003") || thrown.getMessage().contains("서로 다른 목표 상태"),
                "배포하지 않은 설비가 어떤 상태가 되어야 하는지 정할 수 없다는 사실이 드러나야 합니다. message="
                        + thrown.getMessage());
    }

    private static DeployOperationAcceptanceService service(
            final RecordingLedger ledger,
            final List<String> sharedEqpIds
    ) {
        final ObjectMapper objectMapper = new ObjectMapper();
        final UiOperationManifestFactory manifestFactory = new UiOperationManifestFactory(
                objectMapper, new UiRuntimeDescriptorCodec(objectMapper), new Sha256DigestPort());
        final UiResourceHeadPort headPort = headPort();
        final UiCoordinationProperties properties = new UiCoordinationProperties();
        final UiCoordinationMetrics metrics = new UiCoordinationMetrics(new SimpleMeterRegistry());
        /*
         * 접수만 검증하므로 session 을 fence 해 둔다. 살아 있는 session 이면 접수 직후 reconcileOne 이
         * 상태 기계를 전진시켜, 이 테스트가 접수 결과가 아니라 실행 결과를 보게 된다.
         */
        final UiCoordinationSession fenced =
                new UiCoordinationSession("tc-ui-backend", "pod", "boot", "pod");
        fenced.fence();
        final UiOperationExecutor executor = new UiOperationExecutor(
                fenced, properties, ledger, headPort, leasePort(), membership(), publisher(),
                (operationId, manifest, revision) ->
                        com.nori.tc.ui.core.coordination.port.UiOperationDbFinalizationPort.Outcome.FINALIZED,
                com.nori.tc.ui.core.coordination.port.UiFinalizedRevisionProvider.absent(),
                new UiOperationManifestReader(objectMapper),
                CompensationIssuerFixture.issuer(ledger, headPort), metrics);

        return new DeployOperationAcceptanceService(
                new UiResourceKeyPlanner(eqpQueryPort(sharedEqpIds)),
                new UiDeployDescriptorFactory(),
                headPort,
                new UiOperationAcceptanceService(ledger, headPort, manifestFactory),
                new UiOperationReconciler(ledger, executor, properties, metrics),
                objectMapper);
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
                return List.of();
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

    private static DeployExecutionTarget target(
            final long deployTargetKey,
            final String eqpId,
            final long currentModelVersionKey,
            final Long desiredModelVersionKey,
            final Long currentBusinessJarRef,
            final Long desiredBusinessJarRef,
            final DeployTargetApp stepApp
    ) {
        final DeployCurrentState currentState = new DeployCurrentState(
                deployTargetKey, eqpId, "SOCKET", "PASSIVE", "10.0.0.1", 6_000,
                currentModelVersionKey, "V1", null, currentBusinessJarRef, null, null, 0, null);
        final DeployDesiredState desiredState = new DeployDesiredState(
                "SOCKET", desiredModelVersionKey, desiredBusinessJarRef, null, "V1",
                "PASSIVE", "10.0.0.1", 6_000, null, null, 0, null);
        final DeployResolvedStepPlan step = new DeployResolvedStepPlan(
                10, "STEP", "step", stepApp, "desc", DeployScenarioAction.UPDATE_MODEL_BINDING,
                15_000L, true, null, false, null, null);
        final DeployTargetPlanDraft planDraft = new DeployTargetPlanDraft(
                eqpId, "SOCKET", currentState, desiredState, Set.of(), "SCN", 1,
                List.of(step), null, null, null, false);
        return new DeployExecutionTarget(
                new PersistedDeployTarget(deployTargetKey, 1, planDraft),
                DeployTargetStatus.PENDING, null, null, DeployStepStatus.PENDING);
    }

    private static UiResourceHeadPort headPort() {
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

    /** 접수된 manifest 와 idempotency key 를 붙잡아 두는 원장입니다. */
    private static final class RecordingLedger implements UiOperationLedgerPort {

        private final List<String> idempotencyKeys = new ArrayList<>();
        private String lastManifest = "";

        @Override
        public CreateResult createOperation(final String operationId, final String manifestSha256,
                final String manifestJson, final Map<String, String> extraFields, final long nowEpochMs) {
            idempotencyKeys.add(extraFields.get("idempotencyKey"));
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
        public List<com.nori.tc.ui.core.coordination.model.UiParticipantProofView> readParticipantProofs(
                final String operationId) {
            return List.of();
        }

        @Override
        public List<String> pendingOperationIds(final long limit) {
            return List.of();
        }

        @Override
        public List<com.nori.tc.ui.core.coordination.model.UiPhaseTransitionView> readPhaseHistory(
                final String operationId) {
            return List.of();
        }
    }
}
