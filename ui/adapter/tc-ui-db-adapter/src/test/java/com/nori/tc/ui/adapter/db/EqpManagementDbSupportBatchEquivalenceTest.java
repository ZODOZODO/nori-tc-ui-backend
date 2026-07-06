package com.nori.tc.ui.adapter.db;

import com.nori.tc.common.shard.ShardGroupResolver;
import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.eqp.store.TcEqpHsmsStore;
import com.nori.tc.db.core.eqp.store.TcEqpLogStore;
import com.nori.tc.db.core.eqp.store.TcEqpParamStore;
import com.nori.tc.db.core.eqp.store.TcEqpParamVersionStore;
import com.nori.tc.db.core.eqp.store.TcEqpPlcStore;
import com.nori.tc.db.core.eqp.store.TcEqpPortStatusStore;
import com.nori.tc.db.core.eqp.store.TcEqpSocketProtocolTypeStore;
import com.nori.tc.db.core.eqp.store.TcEqpSocketStore;
import com.nori.tc.db.core.eqp.store.TcEqpStateHistStore;
import com.nori.tc.db.core.eqp.store.TcEqpStateStore;
import com.nori.tc.db.core.eqp.store.TcEqpStore;
import com.nori.tc.db.core.jar.store.TcJarBusinessStore;
import com.nori.tc.db.core.jar.store.TcJarGatewayStore;
import com.nori.tc.db.core.model.store.TcModelStore;
import com.nori.tc.db.domain.common.eqp.ControlState;
import com.nori.tc.db.domain.common.model.ModelStatus;
import com.nori.tc.db.domain.common.model.ProtocolType;
import com.nori.tc.db.domain.eqp.TcEqp;
import com.nori.tc.db.domain.eqp.TcEqpHsms;
import com.nori.tc.db.domain.eqp.TcEqpLog;
import com.nori.tc.db.domain.eqp.TcEqpParam;
import com.nori.tc.db.domain.eqp.TcEqpPortStatus;
import com.nori.tc.db.domain.eqp.TcEqpSocket;
import com.nori.tc.db.domain.eqp.TcEqpState;
import com.nori.tc.db.domain.common.eqp.LogLevel;
import com.nori.tc.db.domain.model.TcModel;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 3 (deploy-bulk-register-perf) — 배치 스냅샷 조립 동등성 검증.
 *
 * <p>동일한 store row 를 단건 경로({@code findByEqpKey}/{@code findByEqpId}/{@code findByModelVersionKey})와
 * 배치 경로({@code findAll}/{@code findAllByEqpKeyIn})에 각각 제공하고, {@code loadSnapshotByEqpId} 와
 * {@code loadSnapshotsByEqpIds} 가 planner(toEquipmentProfile)가 읽는 필드
 * (eqp/model/hsms/socket/plc/logPolicy/runtimeState/portStatuses/params)에서 동일한 스냅샷을 만드는지 검증한다.</p>
 *
 * <p>배치는 planner 미사용 필드(connectionState/paramVersionMetas/jar)를 의도적으로 생략하므로 그 필드는 비교 대상이 아니다.</p>
 */
class EqpManagementDbSupportBatchEquivalenceTest {

    private static final OffsetDateTime TS = OffsetDateTime.parse("2026-07-06T10:15:30+09:00");

    private final TcEqpStore eqpStore = mock(TcEqpStore.class);
    private final TcEqpHsmsStore eqpHsmsStore = mock(TcEqpHsmsStore.class);
    private final TcEqpSocketStore eqpSocketStore = mock(TcEqpSocketStore.class);
    private final TcEqpPlcStore eqpPlcStore = mock(TcEqpPlcStore.class);
    private final TcEqpLogStore eqpLogStore = mock(TcEqpLogStore.class);
    private final TcEqpStateStore eqpStateStore = mock(TcEqpStateStore.class);
    private final TcEqpStateHistStore eqpStateHistStore = mock(TcEqpStateHistStore.class);
    private final TcEqpPortStatusStore eqpPortStatusStore = mock(TcEqpPortStatusStore.class);
    private final TcEqpParamStore eqpParamStore = mock(TcEqpParamStore.class);
    private final TcEqpParamVersionStore eqpParamVersionStore = mock(TcEqpParamVersionStore.class);
    private final TcJarGatewayStore jarGatewayStore = mock(TcJarGatewayStore.class);
    private final TcJarBusinessStore jarBusinessStore = mock(TcJarBusinessStore.class);
    private final TcModelStore modelStore = mock(TcModelStore.class);
    private final TcEqpSocketProtocolTypeStore socketProtocolTypeStore = mock(TcEqpSocketProtocolTypeStore.class);
    private final ShardGroupResolver shardGroupResolver = mock(ShardGroupResolver.class);

    private final EqpManagementDbSupport dbSupport = new EqpManagementDbSupport(
            eqpStore, eqpHsmsStore, eqpSocketStore, eqpPlcStore, eqpLogStore, eqpStateStore, eqpStateHistStore,
            eqpPortStatusStore, eqpParamStore, eqpParamVersionStore, jarGatewayStore, jarBusinessStore,
            modelStore, socketProtocolTypeStore, shardGroupResolver);

    @Test
    void batchSnapshotMatchesSingleForEachEqp() {
        // E1: SECS(hsms) + model + log + state + portStatus 2 + param 3(정렬 필요) / E2: SOCKET(socket)
        final TcEqp e1 = eqp(1L, "E1", ProtocolType.SECS, 10L);
        final TcEqp e2 = eqp(2L, "E2", ProtocolType.SOCKET, 20L);
        final TcModel m1 = model(10L, 100L, "MODEL_A");
        final TcModel m2 = model(20L, 200L, "MODEL_B");
        final TcEqpHsms hsms1 = new TcEqpHsms(1L, 5, 45, 10, 5, 10, 5, true, 30, 4096L, TS, TS);
        final TcEqpSocket socket2 = new TcEqpSocket(2L, "ASCII", "UTF-8", false, 0, 3000, 3000, 8192, true, TS, TS);
        final TcEqpLog log1 = new TcEqpLog(1L, LogLevel.INFO, 30, "/var/log/e1", TS);
        final TcEqpState state1 = new TcEqpState(1L, ControlState.REMOTE, null, null, TS, null, null, TS);
        final TcEqpState state2 = new TcEqpState(2L, ControlState.LOCAL, null, null, TS, null, null, TS);
        final TcEqpPortStatus ps1 = new TcEqpPortStatus(101L, 1L, "P1", null, "IDLE", null, null, null, TS);
        final TcEqpPortStatus ps2 = new TcEqpPortStatus(102L, 1L, "P2", null, "RUN", null, null, null, TS);
        // param: version desc, name asc(ignoreCase) 정렬 확인용으로 일부러 뒤섞어 제공
        final TcEqpParam pA = param(201L, 1L, "ALPHA", "V1");
        final TcEqpParam pB = param(202L, 1L, "beta", "V2");
        final TcEqpParam pC = param(203L, 1L, "GAMMA", "V2");

        // --- 단건 경로 stubs ---
        when(eqpStore.findByEqpId("E1")).thenReturn(Optional.of(e1));
        when(modelStore.findByModelVersionKey(10L)).thenReturn(Optional.of(m1));
        when(eqpHsmsStore.findByEqpKey(1L)).thenReturn(Optional.of(hsms1));
        when(eqpLogStore.findByEqpKey(1L)).thenReturn(Optional.of(log1));
        when(eqpStateStore.findByEqpKey(1L)).thenReturn(Optional.of(state1));
        when(eqpPortStatusStore.findAllByEqpKey(eq(1L), any(PageRequest.class))).thenReturn(List.of(ps1, ps2));
        when(eqpParamStore.findAllByEqpKey(eq(1L), any(PageRequest.class))).thenReturn(List.of(pA, pB, pC));

        when(eqpStore.findByEqpId("E2")).thenReturn(Optional.of(e2));
        when(modelStore.findByModelVersionKey(20L)).thenReturn(Optional.of(m2));
        when(eqpSocketStore.findByEqpKey(2L)).thenReturn(Optional.of(socket2));
        when(eqpStateStore.findByEqpKey(2L)).thenReturn(Optional.of(state2));

        // --- 배치 경로 stubs ---
        when(eqpStore.findAll(any(PageRequest.class))).thenReturn(List.of(e1, e2));
        when(modelStore.findAll(any(PageRequest.class))).thenReturn(List.of(m1, m2));
        final List<Long> keys = List.of(1L, 2L);
        when(eqpHsmsStore.findAllByEqpKeyIn(keys)).thenReturn(List.of(hsms1));
        when(eqpSocketStore.findAllByEqpKeyIn(keys)).thenReturn(List.of(socket2));
        when(eqpPlcStore.findAllByEqpKeyIn(keys)).thenReturn(List.of());
        when(eqpLogStore.findAllByEqpKeyIn(keys)).thenReturn(List.of(log1));
        when(eqpStateStore.findAllByEqpKeyIn(keys)).thenReturn(List.of(state1, state2));
        when(eqpPortStatusStore.findAllByEqpKeyIn(keys)).thenReturn(List.of(ps1, ps2));
        when(eqpParamStore.findAllByEqpKeyIn(keys)).thenReturn(List.of(pA, pB, pC));

        final Map<String, EqpManagementSnapshot> batch = dbSupport.loadSnapshotsByEqpIds(List.of("E1", "E2"));

        assertPlannerFieldsEqual(dbSupport.loadSnapshotByEqpId("E1").orElseThrow(), batch.get("E1"));
        assertPlannerFieldsEqual(dbSupport.loadSnapshotByEqpId("E2").orElseThrow(), batch.get("E2"));

        // param 정렬 동등성 명시 확인(version desc, name asc ignoreCase → V2 beta, V2 GAMMA, V1 ALPHA)
        assertEquals(
                List.of("beta", "GAMMA", "ALPHA"),
                batch.get("E1").params().stream().map(p -> p.paramName()).toList()
        );
    }

    @Test
    void emptyInputAndMissingEqpId() {
        assertTrue(dbSupport.loadSnapshotsByEqpIds(List.of()).isEmpty());

        final TcEqp e1 = eqp(1L, "E1", ProtocolType.SECS, 10L);
        when(eqpStore.findAll(any(PageRequest.class))).thenReturn(List.of(e1));
        when(modelStore.findAll(any(PageRequest.class))).thenReturn(List.of());

        final Map<String, EqpManagementSnapshot> result = dbSupport.loadSnapshotsByEqpIds(List.of("E1", "MISSING"));
        assertEquals(1, result.size());
        assertNotNull(result.get("E1"));
        assertFalse(result.containsKey("MISSING"));
    }

    private static void assertPlannerFieldsEqual(final EqpManagementSnapshot single, final EqpManagementSnapshot batch) {
        assertNotNull(batch);
        assertEquals(single.eqp(), batch.eqp());
        assertEquals(single.model(), batch.model());
        assertEquals(single.hsms(), batch.hsms());
        assertEquals(single.socket(), batch.socket());
        assertEquals(single.plc(), batch.plc());
        assertEquals(single.logPolicy(), batch.logPolicy());
        assertEquals(single.runtimeState(), batch.runtimeState());
        assertEquals(single.portStatuses(), batch.portStatuses());
        assertEquals(single.params(), batch.params());
    }

    private static TcEqp eqp(final long eqpKey, final String eqpId, final ProtocolType iface, final long modelVersionKey) {
        return new TcEqp(
                eqpKey, eqpId, modelVersionKey, iface, "ACTIVE", false, 1, "127.0.0.1", 5000,
                301L, null, null, null, true, TS, TS, "SYSTEM", "SYSTEM");
    }

    private static TcModel model(final long modelVersionKey, final long modelKey, final String name) {
        return new TcModel(
                modelVersionKey, modelKey, name, null, "v1", ProtocolType.SECS, ModelStatus.OPERATE,
                null, null, TS, TS, "SYSTEM", "SYSTEM");
    }

    private static TcEqpParam param(final long key, final long eqpKey, final String name, final String version) {
        return new TcEqpParam(key, eqpKey, name, version, "val", null, "SYSTEM", TS);
    }
}
