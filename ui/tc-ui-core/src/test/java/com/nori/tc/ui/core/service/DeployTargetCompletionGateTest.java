package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
import com.nori.tc.ui.core.coordination.model.UiOperationRequest;
import com.nori.tc.ui.core.coordination.port.UiOperationDbFinalizationPort;
import com.nori.tc.ui.core.deploy.DeployCurrentState;
import com.nori.tc.ui.core.deploy.DeployDesiredState;
import com.nori.tc.ui.core.deploy.DeployExecutionPlan;
import com.nori.tc.ui.core.deploy.DeployExecutionTarget;
import com.nori.tc.ui.core.deploy.DeployResolvedStepPlan;
import com.nori.tc.ui.core.deploy.DeployScenarioAction;
import com.nori.tc.ui.core.deploy.DeployStepStatus;
import com.nori.tc.ui.core.deploy.DeployTargetApp;
import com.nori.tc.ui.core.deploy.DeployTargetPlanDraft;
import com.nori.tc.ui.core.deploy.DeployTargetStatus;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.port.db.DeployStorePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * target {@code COMPLETED} 를 쓰는 지점을 고정합니다(§13.6).
 *
 * <p>§13.6 은 "모든 applied proof 와 DB finalization 이 끝나기 전에 target {@code COMPLETED} 또는
 * UI PASS 를 기록하지 않는다" 를 요구합니다. 그래서 step 을 미는 pump 는 완료를 쓰지 않고, operation
 * 이 finalization 단계에 도달했을 때만 이 finalizer 가 씁니다.</p>
 */
class DeployTargetCompletionGateTest {

    private static final String OPERATION_ID = "op-deploy-1";
    private static final long DEPLOY_KEY = 77L;
    private static final long TARGET_A = 11L;
    private static final long TARGET_B = 12L;

    @Test
    @DisplayName("finalization 단계에서만 그룹의 모든 target 을 완료로 확정한다")
    void finalizationCompletesEveryTargetInTheGroup() {
        final DeployStorePort storePort = mock(DeployStorePort.class);
        final DeployEqpFinalizationService eqpFinalization = mock(DeployEqpFinalizationService.class);
        final DeployCommandAssembler assembler = mock(DeployCommandAssembler.class);
        when(storePort.findDeployExecutionPlan(DEPLOY_KEY))
                .thenReturn(Optional.of(plan(DeployTargetStatus.PENDING, DeployTargetStatus.PENDING)));

        final UiOperationDbFinalizationPort.Outcome outcome =
                finalizer(storePort, eqpFinalization, assembler)
                        .finalizeToTarget(OPERATION_ID, manifest(), 7L);

        assertEquals(UiOperationDbFinalizationPort.Outcome.FINALIZED, outcome);
        verify(storePort).markTargetCompleted(eq(TARGET_A), any());
        verify(storePort).markTargetCompleted(eq(TARGET_B), any());
        verify(eqpFinalization, times(2)).finalizeTargetSuccess(any(), eq("tester"));
        verify(assembler).refreshDeployStatus(DEPLOY_KEY, null, null);
    }

    @Test
    @DisplayName("이미 완료된 target 은 다시 확정하지 않고 성공으로 reconcile 한다")
    void alreadyCompletedTargetsAreReconciled() {
        final DeployStorePort storePort = mock(DeployStorePort.class);
        final DeployEqpFinalizationService eqpFinalization = mock(DeployEqpFinalizationService.class);
        final DeployCommandAssembler assembler = mock(DeployCommandAssembler.class);
        when(storePort.findDeployExecutionPlan(DEPLOY_KEY))
                .thenReturn(Optional.of(plan(DeployTargetStatus.COMPLETED, DeployTargetStatus.COMPLETED)));

        final UiOperationDbFinalizationPort.Outcome outcome =
                finalizer(storePort, eqpFinalization, assembler)
                        .finalizeToTarget(OPERATION_ID, manifest(), 7L);

        assertEquals(UiOperationDbFinalizationPort.Outcome.ALREADY_AT_TARGET, outcome,
                "executor 가 확정 직후 죽으면 인계 Pod 가 같은 확정을 반복합니다 — 같은 결과여야 합니다.");
        verify(storePort, never()).markTargetCompleted(anyLong(), any());
        verify(eqpFinalization, never()).finalizeTargetSuccess(any(), any());
    }

    @Test
    @DisplayName("확정할 target 정보가 없으면 덮어쓰지 않고 예상 밖 상태로 보고한다")
    void missingPlanIsUnexpectedState() {
        final DeployStorePort storePort = mock(DeployStorePort.class);
        when(storePort.findDeployExecutionPlan(DEPLOY_KEY)).thenReturn(Optional.empty());

        final UiOperationDbFinalizationPort.Outcome outcome = finalizer(
                storePort, mock(DeployEqpFinalizationService.class), mock(DeployCommandAssembler.class))
                .finalizeToTarget(OPERATION_ID, manifest(), 7L);

        assertEquals(UiOperationDbFinalizationPort.Outcome.UNEXPECTED_STATE, outcome);
        verify(storePort, never()).markTargetCompleted(anyLong(), any());
    }

    private static UiDeployOperationDbFinalizer finalizer(
            final DeployStorePort storePort,
            final DeployEqpFinalizationService eqpFinalization,
            final DeployCommandAssembler assembler
    ) {
        return new UiDeployOperationDbFinalizer(
                storePort, eqpFinalization, assembler, new ObjectMapper());
    }

    private static UiOperationManifestView manifest() {
        return new UiOperationManifestView(
                "EQP_UPDATE", "EQP001", "tester", "DEPLOY:" + DEPLOY_KEY + ":" + TARGET_A,
                "SOCKET", false,
                List.of("equipment:EQP001"), Map.of("equipment:EQP001", "{}"),
                Map.of("equipment:EQP001", 0L), null, null, Map.of(),
                UiOperationRequest.DB_COMMAND_DEPLOY_TARGET,
                "{\"deployKey\":" + DEPLOY_KEY + ",\"deployTargetKeys\":[" + TARGET_A + "," + TARGET_B
                        + "],\"actor\":\"tester\"}");
    }

    private static DeployExecutionPlan plan(
            final DeployTargetStatus statusA,
            final DeployTargetStatus statusB
    ) {
        return new DeployExecutionPlan(DEPLOY_KEY, UiCommandEventType.EQP_UPDATE, List.of(
                target(TARGET_A, "EQP001", statusA),
                target(TARGET_B, "EQP002", statusB)));
    }

    private static DeployExecutionTarget target(
            final long deployTargetKey,
            final String eqpId,
            final DeployTargetStatus status
    ) {
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
                status, null, null, DeployStepStatus.PENDING, null);
    }
}
