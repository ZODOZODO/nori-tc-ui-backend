package com.nori.tc.ui.core.coordination;

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
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * deploy 취소를 커밋 경계로 가르는 계약을 고정합니다(§7.2 / Plan D3).
 *
 * <p>커밋 전 operation 은 candidate abort 로 되돌릴 수 있습니다. 커밋 이후에는 head 가 이미
 * 전진했고 participant 가 적용 중이므로 여기서 중단하면 어느 설비가 새 상태이고 어느 설비가 옛
 * 상태인지 아무도 알 수 없습니다 — 그래서 취소가 아니라 <b>거부</b>여야 합니다.</p>
 */
class DeployCommitBoundaryCancelTest {

    private static final long DEPLOY_KEY = 77L;

    @Test
    @DisplayName("커밋 전 operation 은 취소된다")
    void beforeCommitIsAborted() {
        final UiOperationExecutor executor = mock(UiOperationExecutor.class);
        when(executor.abortBeforeCommit(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);

        final DeployOperationCoordinator.CancelOutcome outcome = coordinator(executor)
                .cancelBeforeCommit(DEPLOY_KEY, UiCommandEventType.EQP_UPDATE, List.of(target(11L, "EQP001")));

        assertEquals(1, outcome.abortedOperationIds().size());
        assertTrue(outcome.committedOperationIds().isEmpty());
    }

    @Test
    @DisplayName("커밋 이후 operation 은 취소되지 않고 커밋 목록으로 보고된다")
    void afterCommitIsRefused() {
        final UiOperationExecutor executor = mock(UiOperationExecutor.class);
        when(executor.abortBeforeCommit(anyString(), anyString(), anyString(), anyLong())).thenReturn(false);

        final DeployOperationCoordinator.CancelOutcome outcome = coordinator(executor)
                .cancelBeforeCommit(DEPLOY_KEY, UiCommandEventType.EQP_UPDATE, List.of(target(11L, "EQP001")));

        assertTrue(outcome.abortedOperationIds().isEmpty(),
                "커밋된 revision 을 여기서 되돌리면 §7.2 가 깨집니다 — 보상 경로로만 되돌립니다.");
        assertEquals(1, outcome.committedOperationIds().size());
    }

    @Test
    @DisplayName("취소는 접수와 같은 그룹 key 로 operation 을 찾는다")
    void cancelTargetsTheSameOperationIdAsAcceptance() {
        final UiOperationExecutor executor = mock(UiOperationExecutor.class);
        final UiOperationManifestFactory manifestFactory = manifestFactory();
        final String expected = manifestFactory.operationIdOf(
                DeployOperationCoordinator.executionIdOf(DEPLOY_KEY, 11L));
        when(executor.abortBeforeCommit(eq(expected), anyString(), anyString(), anyLong())).thenReturn(true);

        final DeployOperationCoordinator.CancelOutcome outcome =
                coordinator(executor, manifestFactory).cancelBeforeCommit(
                        DEPLOY_KEY, UiCommandEventType.EQP_UPDATE,
                        List.of(target(11L, "EQP001"), target(12L, "EQP002")));

        assertEquals(List.of(expected), outcome.abortedOperationIds(),
                "그룹 key 는 최소 target key 로 도출되므로 접수와 취소가 같은 operation 을 가리켜야 합니다.");
        assertEquals(1, outcome.committedOperationIds().size(),
                "EQP002 그룹은 stub 이 false 를 돌려주므로 커밋 목록에 남습니다.");
    }

    @Test
    @DisplayName("NO_CHANGE target 이 섞여 있어도 취소는 접수와 같은 operation 을 가리킨다")
    void noChangeTargetDoesNotShiftTheCancelOperationId() {
        final UiOperationManifestFactory manifestFactory = manifestFactory();
        // 접수는 실행 대상만 넘긴다(DeployService 가 NO_CHANGE 를 걸러낸다). 그때 만들어진 operationId.
        final String accepted = manifestFactory.operationIdOf(
                DeployOperationCoordinator.executionIdOf(DEPLOY_KEY, 12L));

        final UiOperationExecutor executor = mock(UiOperationExecutor.class);
        when(executor.abortBeforeCommit(anyString(), anyString(), anyString(), anyLong())).thenReturn(false);
        when(executor.abortBeforeCommit(eq(accepted), anyString(), anyString(), anyLong())).thenReturn(true);

        // 취소는 계획 전체를 넘긴다 — NO_CHANGE target 은 접수된 적이 없다.
        final DeployOperationCoordinator.CancelOutcome outcome =
                coordinator(executor, manifestFactory).cancelBeforeCommit(
                        DEPLOY_KEY, UiCommandEventType.EQP_UPDATE,
                        List.of(noChangeTarget(11L, "EQP001"), target(12L, "EQP002")));

        assertEquals(List.of(accepted), outcome.abortedOperationIds(),
                "NO_CHANGE target 은 접수되지 않았으므로 취소 그룹에서도 빠져야 합니다.");
        assertTrue(outcome.committedOperationIds().isEmpty(),
                "존재한 적 없는 operation 을 '이미 커밋됨' 으로 보고하면 취소가 영구히 거부됩니다.");
    }

    @Test
    @DisplayName("NO_CHANGE target 이 같은 모델을 공유해도 대표 target key 를 밀어내지 않는다")
    void noChangeTargetSharingTheModelDoesNotShiftTheRepresentative() {
        final UiOperationManifestFactory manifestFactory = manifestFactory();
        final String accepted = manifestFactory.operationIdOf(
                DeployOperationCoordinator.executionIdOf(DEPLOY_KEY, 12L));

        final UiOperationExecutor executor = mock(UiOperationExecutor.class);
        when(executor.abortBeforeCommit(anyString(), anyString(), anyString(), anyLong())).thenReturn(false);
        when(executor.abortBeforeCommit(eq(accepted), anyString(), anyString(), anyLong())).thenReturn(true);

        // 두 설비가 같은 modelVersionKey 를 쓰면 영향 집합이 겹쳐 한 그룹으로 합쳐진다. NO_CHANGE 인
        // 11 이 그룹에 남으면 대표 key 가 11 로 밀려 접수된 operation 을 영영 찾지 못한다.
        final DeployOperationCoordinator.CancelOutcome outcome = new DeployOperationCoordinator(
                new UiResourceKeyPlanner(sharedModelEqpQueryPort()),
                new UiDeployDescriptorFactory(),
                mock(com.nori.tc.ui.core.coordination.port.UiResourceHeadPort.class),
                mock(UiOperationAcceptanceService.class),
                mock(UiOperationReconciler.class),
                executor,
                manifestFactory,
                new ObjectMapper()
        ).cancelBeforeCommit(
                DEPLOY_KEY, UiCommandEventType.EQP_MODEL_UPDATE,
                List.of(noChangeTarget(11L, "EQP001"), target(12L, "EQP002")));

        assertEquals(List.of(accepted), outcome.abortedOperationIds(),
                "대표 target key 가 밀리면 접수된 operation 이 취소되지 않고 계속 진행됩니다.");
        assertTrue(outcome.committedOperationIds().isEmpty());
    }

    private static DeployOperationCoordinator coordinator(final UiOperationExecutor executor) {
        return coordinator(executor, manifestFactory());
    }

    private static DeployOperationCoordinator coordinator(
            final UiOperationExecutor executor,
            final UiOperationManifestFactory manifestFactory
    ) {
        return new DeployOperationCoordinator(
                new UiResourceKeyPlanner(eqpQueryPort()),
                new UiDeployDescriptorFactory(),
                mock(com.nori.tc.ui.core.coordination.port.UiResourceHeadPort.class),
                mock(UiOperationAcceptanceService.class),
                mock(UiOperationReconciler.class),
                executor,
                manifestFactory,
                new ObjectMapper());
    }

    private static UiOperationManifestFactory manifestFactory() {
        final ObjectMapper objectMapper = new ObjectMapper();
        return new UiOperationManifestFactory(
                objectMapper, new UiRuntimeDescriptorCodec(objectMapper), new Sha256DigestPort());
    }

    /** operationId 도출이 실제 digest 를 쓰므로 형식이 맞는 값을 돌려주는 대역입니다. */
    private static final class Sha256DigestPort
            implements com.nori.tc.ui.core.coordination.port.UiCoordinationDigestPort {

        @Override
        public String canonicalize(final String json) {
            return json;
        }

        @Override
        public String sha256Hex(final String canonicalJson) {
            try {
                return java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256")
                                .digest(canonicalJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    /**
     * 변경 없음으로 계획된 target 입니다.
     *
     * <p>계획 시점에 {@code NO_CHANGE} 로 저장되며 실행 대상에서 빠집니다
     * ({@code DeployJdbcStoreSupport:198}). 상태는 이후에도 바뀌지 않으므로 취소 시점에도 그대로
     * 조회됩니다 — 그래서 접수와 취소의 그룹 입력이 갈립니다.</p>
     */
    private static DeployExecutionTarget noChangeTarget(final long deployTargetKey, final String eqpId) {
        final DeployExecutionTarget changed = target(deployTargetKey, eqpId);
        final DeployTargetPlanDraft draft = changed.target().planDraft();
        final DeployTargetPlanDraft noChangeDraft = new DeployTargetPlanDraft(
                draft.eqpId(), draft.interfaceType(), draft.currentState(), draft.desiredState(),
                draft.deltaKinds(), draft.resolvedScenarioCode(), draft.resolvedScenarioVersion(),
                draft.resolvedSteps(), draft.desiredStateJson(), draft.deltaJson(),
                draft.resolvedStepPlanJson(), true);
        return new DeployExecutionTarget(
                new PersistedDeployTarget(deployTargetKey, changed.target().targetOrder(), noChangeDraft),
                DeployTargetStatus.NO_CHANGE, null, null, DeployStepStatus.PENDING);
    }

    /** 두 설비가 같은 {@code modelVersionKey} 를 공유해 영향 집합이 겹치는 구성입니다. */
    private static EqpQueryPort sharedModelEqpQueryPort() {
        final EqpQueryPort delegate = eqpQueryPort();
        return new EqpQueryPort() {
            @Override
            public PagedResponse<UiEqp> findAll(final UiPageRequest pageRequest) {
                return delegate.findAll(pageRequest);
            }

            @Override
            public Optional<UiEqp> findByEqpId(final String eqpId) {
                return delegate.findByEqpId(eqpId);
            }

            @Override
            public List<String> findParamVersionsByEqpId(final String eqpId) {
                return delegate.findParamVersionsByEqpId(eqpId);
            }

            @Override
            public List<UiEqpParam> findParamsByEqpIdAndVersion(final String eqpId, final String version) {
                return delegate.findParamsByEqpIdAndVersion(eqpId, version);
            }

            @Override
            public Optional<EqpCheckoutStateView> findCheckoutStateByEqpId(final String eqpId) {
                return delegate.findCheckoutStateByEqpId(eqpId);
            }

            @Override
            public Optional<EqpRuntimeStateView> findRuntimeStateByEqpId(final String eqpId) {
                return delegate.findRuntimeStateByEqpId(eqpId);
            }

            @Override
            public List<String> findEqpIdsByModelVersionKey(final long modelVersionKey) {
                return List.of("EQP001", "EQP002");
            }
        };
    }

    private static DeployExecutionTarget target(final long deployTargetKey, final String eqpId) {
        final DeployTargetPlanDraft planDraft = new DeployTargetPlanDraft(
                eqpId, "SOCKET",
                new DeployCurrentState(deployTargetKey, eqpId, "SOCKET", "PASSIVE", "10.0.0.1", 6_000,
                        1_001L, "V1", null, 30L, null, null, 0, null),
                new DeployDesiredState("SOCKET", 1_001L, 31L, null, "V1",
                        "PASSIVE", "10.0.0.1", 6_000, null, null, 0, null),
                Set.of(), "SCN", 1,
                List.of(new DeployResolvedStepPlan(10, "STEP_A", "STEP_A", DeployTargetApp.BUSINESS,
                        "desc", DeployScenarioAction.UPDATE_MODEL_BINDING, 15_000L, true,
                        null, false, null, null)),
                null, null, null, false);
        return new DeployExecutionTarget(
                new PersistedDeployTarget(deployTargetKey, 1, planDraft),
                DeployTargetStatus.PENDING, null, null, DeployStepStatus.PENDING);
    }

    private static EqpQueryPort eqpQueryPort() {
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
                return List.of();
            }
        };
    }
}
