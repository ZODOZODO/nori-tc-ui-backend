package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
import com.nori.tc.ui.core.coordination.model.UiOperationRequest;
import com.nori.tc.ui.core.coordination.port.UiOperationApplyDriver;
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
import com.nori.tc.ui.core.deploy.DeployTraceCompletion;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.model.UiCommandCoordination;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.port.db.DeployStorePort;
import com.nori.tc.ui.core.registry.DeployTraceRedisLookupResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * deploy operation 의 {@code APPLYING} 단계 pump 를 고정합니다(§13.6 / Plan D5).
 *
 * <p>세 가지가 계약입니다.</p>
 * <ol>
 *   <li>한 패스는 <b>블로킹하지 않는다</b> — 응답을 기다리는 중이면 아무것도 발행하지 않고 즉시 반환</li>
 *   <li>step 명령에 coordination metadata 를 싣는다 — 없으면 조정 구성의 Business 가 거부한다</li>
 *   <li>인계 Pod 가 영속 상태만 보고 같은 지점에서 이어간다 — 로컬 future 를 쓰지 않는다</li>
 * </ol>
 */
class DeployApplyDriverTest {

    private static final String OPERATION_ID = "op-deploy-1";
    private static final long DEPLOY_KEY = 77L;
    private static final long TARGET_KEY = 11L;
    private static final String EQP_ID = "EQP001";
    private static final String MANIFEST_SHA = "a".repeat(64);

    @Test
    @DisplayName("첫 패스는 첫 step 을 coordination metadata 와 함께 발행한다")
    void firstPassPublishesFirstStepWithCoordinationMetadata() {
        final Fixture fixture = new Fixture();

        final UiOperationApplyDriver.Result result = fixture.drive();

        assertEquals(UiOperationApplyDriver.Progress.ADVANCED, result.progress());

        final ArgumentCaptor<UiCommandCoordination> coordination =
                ArgumentCaptor.forClass(UiCommandCoordination.class);
        verify(fixture.commandAssembler).publishStepCommand(
                eq(DEPLOY_KEY), any(), any(), any(), anyString(), anyString(), coordination.capture());

        final UiCommandCoordination captured = coordination.getValue();
        assertNotNull(captured, "coordination 이 비면 Business 가 COORDINATION_METADATA_REQUIRED 로 거부합니다.");
        assertEquals(OPERATION_ID, captured.operationId());
        assertEquals(7L, captured.desiredRevision());
        assertEquals(MANIFEST_SHA, captured.payloadSha256());
        assertEquals(UiCommandCoordination.PHASE_APPLY, captured.phase());
    }

    @Test
    @DisplayName("응답을 기다리는 중에는 아무것도 발행하지 않고 즉시 반환한다")
    void waitingPassPublishesNothing() {
        final Fixture fixture = new Fixture();
        fixture.drive();
        fixture.tracePending();

        final UiOperationApplyDriver.Result result = fixture.drive();

        assertEquals(UiOperationApplyDriver.Progress.WAITING, result.progress(),
                "여기서 블로킹하면 executor lease(15s)가 step timeout(60s) 안에 만료됩니다.");
        // 첫 패스의 1 회 발행 그대로 — 대기 중 재발행이 없어야 한다.
        verify(fixture.commandAssembler, times(1)).publishStepCommand(
                anyLong(), any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("step 응답이 성공이면 체크포인트를 남기고 다음 step 을 발행한다")
    void successfulStepCheckpointsAndAdvances() {
        final Fixture fixture = new Fixture();
        fixture.drive();
        fixture.traceSucceeded();

        assertEquals(UiOperationApplyDriver.Progress.ADVANCED, fixture.drive().progress());

        verify(fixture.deployStorePort).markTargetReadyForResume(
                eq(TARGET_KEY), eq(10), eq("STEP_A"), any());
        verify(fixture.deployStorePort, never()).markTargetCompleted(anyLong(), any());

        // 체크포인트가 반영된 다음 패스가 두 번째 step 을 발행한다.
        assertEquals(UiOperationApplyDriver.Progress.ADVANCED, fixture.drive().progress());
        verify(fixture.commandAssembler, times(2)).publishStepCommand(
                anyLong(), any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("인계 Pod 는 영속 체크포인트만 보고 같은 traceId 로 이어간다")
    void takeoverResumesFromPersistedCheckpointWithSameTraceId() {
        final Fixture fixture = new Fixture();
        fixture.drive();

        // 새 driver = 로컬 상태가 전혀 없는 다른 Pod. 영속 상태만 공유한다.
        final DeployApplyDriver takeover = new DeployApplyDriver(
                fixture.deployStorePort, fixture.commandAssembler,
                fixture.traceRecorder, new ObjectMapper());

        assertEquals(UiOperationApplyDriver.Progress.WAITING, fixture.drive(takeover).progress(),
                "인계 Pod 는 발행해 둔 step 의 응답을 기다려야 합니다 — 여기서 다시 보내면 중복 발행입니다.");
        verify(fixture.commandAssembler, times(1)).publishStepCommand(
                anyLong(), any(), any(), any(), anyString(), anyString(), any());

        fixture.traceSucceeded();
        assertEquals(UiOperationApplyDriver.Progress.ADVANCED, fixture.drive(takeover).progress());
        assertEquals(UiOperationApplyDriver.Progress.ADVANCED, fixture.drive(takeover).progress());

        final ArgumentCaptor<String> traceIds = ArgumentCaptor.forClass(String.class);
        verify(fixture.commandAssembler, times(2)).publishStepCommand(
                anyLong(), any(), any(), any(), traceIds.capture(), anyString(), any());
        assertEquals(
                List.of(DeployApplyDriver.stepTraceId(OPERATION_ID, TARGET_KEY, "STEP_A"),
                        DeployApplyDriver.stepTraceId(OPERATION_ID, TARGET_KEY, "STEP_B")),
                traceIds.getAllValues(),
                "traceId 는 저장하지 않고 도출하므로, 인계 Pod 가 발행해도 같은 값이어야 합니다(§12.6).");
    }

    @Test
    @DisplayName("모든 step 을 보내고 나면 proof 판정 단계로 넘긴다")
    void exhaustedAfterLastStep() {
        final Fixture fixture = new Fixture();
        fixture.drive();
        fixture.traceSucceeded();
        fixture.drive();
        fixture.drive();
        fixture.traceSucceeded();
        fixture.drive();

        assertEquals(UiOperationApplyDriver.Progress.EXHAUSTED, fixture.drive().progress());
    }

    @Test
    @DisplayName("deploy operation 이 아니면 기본 발행에 넘긴다")
    void nonDeployOperationIsNotMine() {
        final Fixture fixture = new Fixture();
        final UiOperationManifestView eqpManifest = new UiOperationManifestView(
                "EQP_UPDATE", EQP_ID, "tester", null, "SOCKET", false,
                List.of("equipment:" + EQP_ID), Map.of("equipment:" + EQP_ID, "{}"),
                Map.of("equipment:" + EQP_ID, 0L), null, null, Map.of(),
                UiOperationRequest.DB_COMMAND_UPDATE, "{}");

        final UiOperationApplyDriver.Result result = fixture.driver.driveApply(
                OPERATION_ID, eqpManifest, 7L, MANIFEST_SHA, "2026-01-01T00:00:00Z", 1_000L);

        assertEquals(UiOperationApplyDriver.Progress.NOT_MINE, result.progress());
        assertNull(result.errorCode());
    }

    @Test
    @DisplayName("step 응답이 실패면 커밋 이후 실패로 보고한다")
    void failedStepIsReportedAsFailure() {
        final Fixture fixture = new Fixture();
        fixture.drive();
        fixture.traceFailed("PLUGIN_LOAD_FAILED", "plugin 적재에 실패했습니다.");

        final UiOperationApplyDriver.Result result = fixture.drive();

        assertEquals(UiOperationApplyDriver.Progress.FAILED, result.progress());
        assertEquals("PLUGIN_LOAD_FAILED", result.errorCode());
        verify(fixture.deployStorePort, never()).markTargetCompleted(anyLong(), any());
    }

    /**
     * pump 를 돌리는 데 필요한 최소 환경입니다.
     *
     * <p>{@code DeployStorePort} 는 <b>상태를 실제로 들고 있는</b> 대역입니다. 순수 mock 으로 두면
     * 체크포인트를 기록하지 않아도 "다음 step 으로 넘어갔다" 는 단정이 통과해 버리고, 이 클래스의
     * 요점인 인계 재개가 검증되지 않습니다.</p>
     */
    private static final class Fixture {

        private final DeployStorePort deployStorePort = mock(DeployStorePort.class);
        private final DeployCommandAssembler commandAssembler = mock(DeployCommandAssembler.class);
        private final DeployTraceRecorder traceRecorder = mock(DeployTraceRecorder.class);
        private final DeployApplyDriver driver;
        private final UiOperationManifestView manifest;

        private Integer currentStepOrder;
        private String currentStepCode;
        private DeployStepStatus currentStepStatus = DeployStepStatus.PENDING;

        private Fixture() {
            this.driver = new DeployApplyDriver(
                    deployStorePort, commandAssembler, traceRecorder, new ObjectMapper());
            this.manifest = new UiOperationManifestView(
                    "EQP_UPDATE", EQP_ID, "tester", "DEPLOY:" + DEPLOY_KEY + ":" + TARGET_KEY,
                    "SOCKET", false,
                    List.of("equipment:" + EQP_ID), Map.of("equipment:" + EQP_ID, "{}"),
                    Map.of("equipment:" + EQP_ID, 0L), null, null, Map.of(),
                    UiOperationRequest.DB_COMMAND_DEPLOY_TARGET,
                    "{\"deployKey\":" + DEPLOY_KEY + ",\"deployTargetKeys\":[" + TARGET_KEY
                            + "],\"actor\":\"tester\"}");

            when(commandAssembler.expectedSources(any(DeployResolvedStepPlan.class)))
                    .thenReturn(Set.of("TC-BUSINESS-APP"));
            when(commandAssembler.captureTargetPreState(any())).thenReturn(Optional.empty());
            when(deployStorePort.findDeployExecutionPlan(DEPLOY_KEY))
                    .thenAnswer(invocation -> Optional.of(executionPlan()));
            tracePending();

            // 영속 상태 대역 — 발행하면 WAITING_REPLY, 체크포인트를 남기면 COMPLETED 로 옮긴다.
            org.mockito.Mockito.doAnswer(invocation -> {
                currentStepOrder = invocation.getArgument(1);
                currentStepCode = invocation.getArgument(2);
                currentStepStatus = DeployStepStatus.WAITING_REPLY;
                return null;
            }).when(deployStorePort).markTargetWaitingReply(
                    anyLong(), anyInt(), anyString(), anyInt(), anyString(), any(), any());

            org.mockito.Mockito.doAnswer(invocation -> {
                currentStepOrder = invocation.getArgument(1);
                currentStepCode = invocation.getArgument(2);
                currentStepStatus = DeployStepStatus.COMPLETED;
                return null;
            }).when(deployStorePort).markTargetReadyForResume(anyLong(), any(), any(), any());
        }

        private UiOperationApplyDriver.Result drive() {
            return drive(driver);
        }

        private UiOperationApplyDriver.Result drive(final UiOperationApplyDriver target) {
            return target.driveApply(
                    OPERATION_ID, manifest, 7L, MANIFEST_SHA, "2026-01-01T00:00:00Z", 1_000L);
        }

        /*
         * 재-stub 은 doAnswer 형식으로만 한다. when(mock.call(...)) 은 이전 stub 을 실제로 한 번
         * 호출해 버려, 두 번째 stub 을 걸 때 null 인자로 옛 stub 이 실행된다.
         */
        private void tracePending() {
            org.mockito.Mockito.doReturn(DeployTraceRedisLookupResult.pending())
                    .when(traceRecorder).completionOf(anyString());
        }

        private void traceSucceeded() {
            org.mockito.Mockito.doAnswer(invocation ->
                            DeployTraceRedisLookupResult.completed(new DeployTraceCompletion(
                                    invocation.getArgument(0), Set.of("TC-BUSINESS-APP"),
                                    List.of(reply(true, null, null)))))
                    .when(traceRecorder).completionOf(anyString());
        }

        private void traceFailed(final String errorCode, final String errorMessage) {
            org.mockito.Mockito.doAnswer(invocation ->
                            DeployTraceRedisLookupResult.completed(new DeployTraceCompletion(
                                    invocation.getArgument(0), Set.of("TC-BUSINESS-APP"),
                                    List.of(reply(false, errorCode, errorMessage)))))
                    .when(traceRecorder).completionOf(anyString());
        }

        private DeployExecutionPlan executionPlan() {
            return new DeployExecutionPlan(DEPLOY_KEY, UiCommandEventType.EQP_UPDATE, List.of(
                    new DeployExecutionTarget(
                            new PersistedDeployTarget(TARGET_KEY, 1, planDraft()),
                            DeployTargetStatus.PENDING,
                            currentStepOrder, currentStepCode, currentStepStatus)));
        }

        private static DeployTargetPlanDraft planDraft() {
            return new DeployTargetPlanDraft(
                    EQP_ID, "SOCKET",
                    new DeployCurrentState(1L, EQP_ID, "SOCKET", "PASSIVE", "10.0.0.1", 6_000,
                            1_001L, "V1", null, 30L, null, null, 0, null),
                    new DeployDesiredState("SOCKET", 1_001L, 31L, null, "V1",
                            "PASSIVE", "10.0.0.1", 6_000, null, null, 0, null),
                    Set.of(), "SCN", 1,
                    List.of(step(10, "STEP_A"), step(20, "STEP_B")),
                    null, null, null, false);
        }

        private static DeployResolvedStepPlan step(final int order, final String code) {
            return new DeployResolvedStepPlan(
                    order, code, code, DeployTargetApp.BUSINESS, "desc",
                    DeployScenarioAction.UPDATE_MODEL_BINDING, 15_000L, true, null, false, null, null);
        }

        private static UiCommandReply reply(
                final boolean success,
                final String errorCode,
                final String errorMessage
        ) {
            return new UiCommandReply(
                    "trace", "TC-BUSINESS-APP", "EQP_UPDATE_REP", EQP_ID, "SOCKET",
                    success
                            ? com.nori.tc.ui.domain.task.UiTaskStatus.PASS
                            : com.nori.tc.ui.domain.task.UiTaskStatus.FAIL,
                    errorCode, errorMessage);
        }
    }
}
