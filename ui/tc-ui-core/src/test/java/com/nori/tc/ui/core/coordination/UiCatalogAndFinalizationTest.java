package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiCatalogHeadView;
import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.coordination.model.UiOperationRequest;
import com.nori.tc.ui.core.coordination.port.UiCatalogHeadPort;
import com.nori.tc.ui.core.coordination.port.UiOperationDbFinalizationPort;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import com.nori.tc.ui.core.service.EqpPersistenceService;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.eqp.UiEqp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §13.7 DB finalization 판정과 §13.9/§13.10 catalog head 동기화를 Docker 없이 고정합니다.
 */
class UiCatalogAndFinalizationTest {

    private static final String EQP_ID = "EQP001";
    private static final String RESOURCE_KEY = "equipment:" + EQP_ID;

    // ---------------------------------------------------------------- catalog

    @Test
    @DisplayName("head 가 비어 있으면 이 Pod 의 digest 로 head 를 만든다")
    void firstPodCommitsTheHead() {
        final InMemoryCatalogHeadPort port = new InMemoryCatalogHeadPort();
        final UiCatalogHeadSynchronizer synchronizer = new UiCatalogHeadSynchronizer(port, "scenario");

        final UiCatalogHeadSynchronizer.SyncState state = synchronizer.reconcile("digest-1", 1_000L);

        assertTrue(state.inSync());
        assertEquals(1L, state.headRevision());
    }

    @Test
    @DisplayName("head 와 같은 digest 면 revision 을 채택만 하고 새로 커밋하지 않는다")
    void matchingDigestAdoptsHeadWithoutCommit() {
        final InMemoryCatalogHeadPort port = new InMemoryCatalogHeadPort();
        final UiCatalogHeadSynchronizer first = new UiCatalogHeadSynchronizer(port, "scenario");
        first.reconcile("digest-1", 1_000L);
        final int commitsAfterFirst = port.commits;

        final UiCatalogHeadSynchronizer second = new UiCatalogHeadSynchronizer(port, "scenario");
        final UiCatalogHeadSynchronizer.SyncState state = second.reconcile("digest-1", 1_000L);

        assertTrue(state.inSync());
        assertEquals(commitsAfterFirst, port.commits, "같은 catalog 를 든 Pod 가 revision 을 올리면 안 됩니다.");
    }

    @Test
    @DisplayName("head 와 다른 digest 를 든 Pod 는 요청을 거부한다")
    void mismatchedPodRejectsRequests() {
        final InMemoryCatalogHeadPort port = new InMemoryCatalogHeadPort();
        new UiCatalogHeadSynchronizer(port, "scenario").reconcile("digest-2", 1_000L);

        final UiCatalogHeadSynchronizer stale = new UiCatalogHeadSynchronizer(port, "scenario");
        assertFalse(stale.verify("digest-1"));
        assertThrows(UiCatalogNotSyncedException.class, () -> stale.requireInSync("digest-1"));
    }

    @Test
    @DisplayName("다른 Pod 이 scenario head 를 옮기면 이 Pod 의 inSync 가 즉시 false 가 된다")
    void scenarioGuardSeesHeadMovedByAnotherPod() {
        final InMemoryCatalogHeadPort port = new InMemoryCatalogHeadPort();
        final UiCatalogHeadSynchronizer synchronizer = new UiCatalogHeadSynchronizer(port, "scenario");
        final CoordinatedUiScenarioCatalogGuard guard = new CoordinatedUiScenarioCatalogGuard(synchronizer);
        guard.onCatalogApplied("digest-1");
        assertTrue(guard.inSync(), "자기 digest 로 head 를 커밋한 직후는 일치해야 합니다.");

        // 다른 Pod 이 새 catalog 를 커밋했다 — 이 Pod 은 reload 도 planning 도 하지 않았다.
        new UiCatalogHeadSynchronizer(port, "scenario").reconcile("digest-2", 2_000L);

        assertFalse(guard.inSync(),
                "§13.11 은 catalog revision mismatch 를 readiness 로 노출하라고 요구합니다 — "
                        + "캐시된 판정을 돌려주면 mismatch Pod 이 Ready 로 남습니다.");
    }

    @Test
    @DisplayName("scenario head 조회가 실패하면 일치로 축약하지 않는다")
    void scenarioGuardFailsClosedWhenHeadIsUnavailable() {
        final InMemoryCatalogHeadPort port = new InMemoryCatalogHeadPort();
        final UiCatalogHeadSynchronizer synchronizer = new UiCatalogHeadSynchronizer(port, "scenario");
        final CoordinatedUiScenarioCatalogGuard guard = new CoordinatedUiScenarioCatalogGuard(synchronizer);
        guard.onCatalogApplied("digest-1");

        port.failReads = true;

        assertFalse(guard.inSync(),
                "Redis 불확실성을 '일치' 로 축약하면 fail-closed 가 깨집니다(§9.3).");
    }

    @Test
    @DisplayName("catalog 를 한 번도 적재하지 못한 Pod 은 inSync 가 아니다")
    void scenarioGuardBeforeFirstLoadIsNotInSync() {
        final InMemoryCatalogHeadPort port = new InMemoryCatalogHeadPort();
        final CoordinatedUiScenarioCatalogGuard guard =
                new CoordinatedUiScenarioCatalogGuard(new UiCatalogHeadSynchronizer(port, "scenario"));

        assertFalse(guard.inSync(), "적재 전 미확정은 Ready 가 아닙니다.");
    }

    // ----------------------------------------------------------- finalization

    @Test
    @DisplayName("DB 가 이미 목표 상태면 성공으로 reconcile 한다")
    void alreadyAtTargetIsReconciledAsSuccess() {
        final EqpCrudPort crudPort = mock(EqpCrudPort.class);
        when(crudPort.findSnapshotByEqpId(EQP_ID)).thenReturn(Optional.of(snapshot(1_002L, 0, 20L)));

        assertEquals(UiOperationDbFinalizationPort.Outcome.ALREADY_AT_TARGET,
                finalizer(crudPort).finalizeToTarget("op-1", manifest(1_002L, 1_001L), 7L));
        verify(crudPort, never()).update(anyString(), any());
    }

    @Test
    @DisplayName("DB 가 manifest 의 이전 상태면 목표 상태로 확정한다")
    void previousStateIsAdvancedToTarget() {
        final EqpCrudPort crudPort = mock(EqpCrudPort.class);
        when(crudPort.findSnapshotByEqpId(EQP_ID)).thenReturn(Optional.of(snapshot(1_001L, 0, 20L)));

        assertEquals(UiOperationDbFinalizationPort.Outcome.FINALIZED,
                finalizer(crudPort).finalizeToTarget("op-1", manifest(1_002L, 1_001L), 7L));
        verify(crudPort).update(anyString(), any());
    }

    @Test
    @DisplayName("DB 가 예상 이전/목표 어느 쪽도 아니면 덮어쓰지 않고 REPAIR_REQUIRED 로 보고한다")
    void unknownStateIsNotOverwritten() {
        final EqpCrudPort crudPort = mock(EqpCrudPort.class);
        when(crudPort.findSnapshotByEqpId(EQP_ID)).thenReturn(Optional.of(snapshot(9_999L, 0, 20L)));

        assertEquals(UiOperationDbFinalizationPort.Outcome.UNEXPECTED_STATE,
                finalizer(crudPort).finalizeToTarget("op-1", manifest(1_002L, 1_001L), 7L));
        verify(crudPort, never()).update(anyString(), any());
    }

    @Test
    @DisplayName("삭제는 대상이 이미 없으면 성공으로 reconcile 한다")
    void deleteIsIdempotent() {
        final EqpCrudPort crudPort = mock(EqpCrudPort.class);
        when(crudPort.findSnapshotByEqpId(EQP_ID)).thenReturn(Optional.empty());

        final UiOperationManifestView deleteManifest = new UiOperationManifestView(
                "EQP_DELETE", EQP_ID, "tester", null, "SOCKET", true,
                List.of(RESOURCE_KEY), Map.of(RESOURCE_KEY, "{\"eqpId\":\"" + EQP_ID + "\",\"deleted\":true}"),
                Map.of(RESOURCE_KEY, 0L), null, null, Map.of(),
                UiOperationRequest.DB_COMMAND_DELETE, "{}");

        assertEquals(UiOperationDbFinalizationPort.Outcome.ALREADY_AT_TARGET,
                finalizer(crudPort).finalizeToTarget("op-1", deleteManifest, 7L));
        verify(crudPort, never()).delete(anyString());
    }

    @Test
    @DisplayName("DB 변경을 동반하지 않는 operation 은 finalization 대상이 아니다")
    void manifestWithoutDbCommandNeedsNoFinalization() {
        final EqpCrudPort crudPort = mock(EqpCrudPort.class);
        when(crudPort.findSnapshotByEqpId(EQP_ID)).thenReturn(Optional.empty());

        final UiOperationManifestView noDbCommand = new UiOperationManifestView(
                "EQP_UPDATE", EQP_ID, "tester", null, "SOCKET", false,
                List.of(RESOURCE_KEY), Map.of(), Map.of(), null, null, Map.of(), null, null);

        assertEquals(UiOperationDbFinalizationPort.Outcome.ALREADY_AT_TARGET,
                finalizer(crudPort).finalizeToTarget("op-1", noDbCommand, 7L));
    }

    @Test
    @DisplayName("§16.1 — 설비 생성은 PREPARE 전에 임시 행을 만든다")
    void createIsStagedBeforePrepare() {
        final EqpCrudPort crudPort = mock(EqpCrudPort.class);
        when(crudPort.findSnapshotByEqpId(EQP_ID)).thenReturn(Optional.empty());

        /*
         * Gateway 는 equipmentProfile.eqpKey 를 양수로 요구하고 적용 단계에서 tc_eqp_state_hist 에
         * 이력을 남긴다 — 그 테이블은 tc_eqp(eqp_key) 를 참조하므로 행이 없으면 참조 무결성으로
         * 실패한다. 즉 행을 만들지 않으면 조정 경로의 설비 생성은 PASS 에 도달할 수 없다.
         */
        assertEquals(UiOperationDbFinalizationPort.Outcome.FINALIZED,
                finalizer(crudPort).stageBeforePrepare("op-1", createManifest()));
        verify(crudPort).create(any());
    }

    @Test
    @DisplayName("§16.1 — 이미 준비된 임시 행은 다시 만들지 않는다")
    void stagingIsIdempotent() {
        final EqpCrudPort crudPort = mock(EqpCrudPort.class);
        when(crudPort.findSnapshotByEqpId(EQP_ID)).thenReturn(Optional.of(snapshot(1_002L, 0, 20L)));

        assertEquals(UiOperationDbFinalizationPort.Outcome.ALREADY_AT_TARGET,
                finalizer(crudPort).stageBeforePrepare("op-1", createManifest()));
        verify(crudPort, never()).create(any());
    }

    @Test
    @DisplayName("§16.1 — 목표와 다른 행이 이미 있으면 준비하지 않고 어긋남을 보고한다")
    void stagingRefusesToTouchADifferentRow() {
        final EqpCrudPort crudPort = mock(EqpCrudPort.class);
        when(crudPort.findSnapshotByEqpId(EQP_ID)).thenReturn(Optional.of(snapshot(9_999L, 0, 20L)));

        assertEquals(UiOperationDbFinalizationPort.Outcome.UNEXPECTED_STATE,
                finalizer(crudPort).stageBeforePrepare("op-1", createManifest()));
        verify(crudPort, never()).create(any());
    }

    @Test
    @DisplayName("§16.1 — abort 는 준비했던 임시 행을 제거한다")
    void abortRemovesTheStagedRow() {
        final EqpCrudPort crudPort = mock(EqpCrudPort.class);
        when(crudPort.findSnapshotByEqpId(EQP_ID)).thenReturn(Optional.of(snapshot(1_002L, 0, 20L)));

        finalizer(crudPort).unstageAfterAbort("op-1", createManifest());

        verify(crudPort).delete(EQP_ID);
    }

    @Test
    @DisplayName("§16.1 — 목표와 다른 행은 abort 에서도 지우지 않는다")
    void abortLeavesForeignRowsAlone() {
        final EqpCrudPort crudPort = mock(EqpCrudPort.class);
        when(crudPort.findSnapshotByEqpId(EQP_ID)).thenReturn(Optional.of(snapshot(9_999L, 0, 20L)));

        finalizer(crudPort).unstageAfterAbort("op-1", createManifest());

        verify(crudPort, never()).delete(anyString());
    }

    @Test
    @DisplayName("수정 operation 에는 준비 단계가 없다")
    void updateHasNoStagingStep() {
        final EqpCrudPort crudPort = mock(EqpCrudPort.class);

        assertEquals(UiOperationDbFinalizationPort.Outcome.ALREADY_AT_TARGET,
                finalizer(crudPort).stageBeforePrepare("op-1", manifest(1_002L, 1_001L)));
        verify(crudPort, never()).create(any());
        verify(crudPort, never()).findSnapshotByEqpId(anyString());
    }

    @Test
    @DisplayName("어댑터가 잘못된 요청으로 분류한 실패는 REJECTED 로 돌려 재시도를 끊는다")
    void invalidCreateRequestIsReportedAsRejected() {
        final EqpCrudPort crudPort = mock(EqpCrudPort.class);
        when(crudPort.findSnapshotByEqpId(EQP_ID)).thenReturn(Optional.empty());
        /*
         * 실제 재현: socketProtocolType 이 참조 테이블에 없어 FK 위반 -> 어댑터가 UiConflictException
         * 으로 변환. 같은 입력을 다시 넣어도 결과가 같으므로 재시도는 의미가 없다.
         */
        doThrow(new UiConflictException("EQP 생성 중 충돌이 발생했습니다."))
                .when(crudPort).create(any());

        assertEquals(UiOperationDbFinalizationPort.Outcome.REJECTED,
                finalizer(crudPort).stageBeforePrepare("op-1", createManifest()));
    }

    @Test
    @DisplayName("일시적 실패는 REJECTED 로 접지 않고 그대로 전파해 재시도를 남긴다")
    void transientFailureIsNotSwallowedAsRejected() {
        final EqpCrudPort crudPort = mock(EqpCrudPort.class);
        when(crudPort.findSnapshotByEqpId(EQP_ID)).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("connection reset")).when(crudPort).create(any());

        assertThrows(IllegalStateException.class,
                () -> finalizer(crudPort).stageBeforePrepare("op-1", createManifest()));
    }

    // ------------------------------------------------------------------ fixture

    /** 설비 생성 operation 의 manifest 입니다 — descriptor 의 목표는 snapshot(1_002L, 0, 20L) 과 같습니다. */
    private static UiOperationManifestView createManifest() {
        return new UiOperationManifestView(
                "EQP_CREATE", EQP_ID, "tester", null, "SOCKET", true,
                List.of(RESOURCE_KEY),
                Map.of(RESOURCE_KEY, descriptorJson(1_002L)),
                Map.of(RESOURCE_KEY, 0L),
                null, null,
                Map.of(RESOURCE_KEY, "{\"schemaVersion\":2,\"eqpId\":\"" + EQP_ID + "\",\"deleted\":true}"),
                UiOperationRequest.DB_COMMAND_CREATE,
                createCommandJson());
    }

    private static String createCommandJson() {
        return "{\"actor\":\"tester\",\"eqpId\":\"" + EQP_ID + "\",\"lineKey\":1,\"interfaceType\":\"SOCKET\","
                + "\"commMode\":\"PASSIVE\",\"isDev\":false,\"routePartition\":0,\"eqpIp\":\"10.0.0.1\","
                + "\"eqpPort\":6000,\"modelVersionKey\":1002,\"appliedParamVersion\":\"V1\","
                + "\"gatewayJarRef\":30,\"businessJarRef\":20}";
    }

    private static UiEqpOperationDbFinalizer finalizer(final EqpCrudPort crudPort) {
        final ObjectMapper objectMapper = new ObjectMapper();
        return new UiEqpOperationDbFinalizer(
                new EqpPersistenceService(crudPort), objectMapper, new UiRuntimeDescriptorReader(objectMapper));
    }

    private static UiOperationManifestView manifest(final long targetModelVersionKey, final long previousKey) {
        return new UiOperationManifestView(
                "EQP_UPDATE", EQP_ID, "tester", null, "SOCKET", false,
                List.of(RESOURCE_KEY),
                Map.of(RESOURCE_KEY, descriptorJson(targetModelVersionKey)),
                Map.of(RESOURCE_KEY, 0L),
                null, null,
                Map.of(RESOURCE_KEY, descriptorJson(previousKey)),
                UiOperationRequest.DB_COMMAND_UPDATE,
                updateCommandJson());
    }

    private static String descriptorJson(final long modelVersionKey) {
        return "{\"schemaVersion\":2,\"eqpId\":\"" + EQP_ID + "\",\"modelVersionKey\":" + modelVersionKey
                + ",\"routePartition\":0,\"businessJarKey\":20}";
    }

    private static String updateCommandJson() {
        return "{\"actor\":\"tester\",\"lineKey\":1,\"commMode\":\"PASSIVE\",\"isDev\":false,"
                + "\"routePartition\":0,\"eqpIp\":\"10.0.0.1\",\"eqpPort\":6000,\"modelVersionKey\":1002,"
                + "\"appliedParamVersion\":\"V1\",\"gatewayJarRef\":30,\"businessJarRef\":20}";
    }

    private static EqpManagementSnapshot snapshot(
            final long modelVersionKey,
            final int routePartition,
            final long businessJarKey
    ) {
        final UiEqp eqp = new UiEqp(
                1L, EQP_ID, 1L, UiProtocolType.SOCKET, "PASSIVE", false, routePartition,
                "10.0.0.1", 6000, modelVersionKey, "V1", 30L, businessJarKey, true,
                OffsetDateTime.now(), OffsetDateTime.now(), "tester", "tester");
        return new EqpManagementSnapshot(
                eqp, null, null, null, null, null, null, null, List.of(), List.of(), List.of(), null, null);
    }

    /** head 를 메모리에 두고 CAS 규칙만 재현하는 포트입니다. */
    private static final class InMemoryCatalogHeadPort implements UiCatalogHeadPort {

        private final Map<String, UiCatalogHeadView> heads = new HashMap<>();
        private int commits;
        /** Redis 불확실성 재현 — head 조회가 예외를 던지는 상태입니다. */
        private boolean failReads;

        @Override
        public String commitHead(final String catalogType, final long newRevision, final String digest,
                final Long expectedRevision, final long nowEpochMs) {
            final UiCatalogHeadView current = readHead(catalogType);
            if (expectedRevision != null && current.revision() != expectedRevision) {
                return "REVISION_CONFLICT";
            }
            if (newRevision <= current.revision()) {
                return "STALE_REVISION";
            }
            heads.put(catalogType, new UiCatalogHeadView(catalogType, newRevision, digest));
            commits++;
            return "OK";
        }

        @Override
        public UiCatalogHeadView readHead(final String catalogType) {
            if (failReads) {
                throw new IllegalStateException("Redis coordination script execution failed");
            }
            return heads.getOrDefault(catalogType, new UiCatalogHeadView(catalogType, 0L, null));
        }
    }
}
