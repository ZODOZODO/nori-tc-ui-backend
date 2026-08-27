package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiCatalogHeadView;
import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
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

    // ------------------------------------------------------------------ fixture

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
