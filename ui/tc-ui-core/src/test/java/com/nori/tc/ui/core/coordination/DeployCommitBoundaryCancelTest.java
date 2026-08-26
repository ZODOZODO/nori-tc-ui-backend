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
