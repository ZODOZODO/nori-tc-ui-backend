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
import com.nori.tc.db.domain.common.eqp.EqpStateType;
import com.nori.tc.db.domain.common.model.ModelStatus;
import com.nori.tc.db.domain.common.model.ProtocolType;
import com.nori.tc.db.domain.eqp.TcEqp;
import com.nori.tc.db.domain.eqp.TcEqpHsms;
import com.nori.tc.db.domain.eqp.TcEqpLog;
import com.nori.tc.db.domain.eqp.TcEqpParam;
import com.nori.tc.db.domain.eqp.TcEqpParamVersion;
import com.nori.tc.db.domain.eqp.TcEqpPortStatus;
import com.nori.tc.db.domain.eqp.TcEqpSocket;
import com.nori.tc.db.domain.eqp.TcEqpState;
import com.nori.tc.db.domain.eqp.TcEqpStateHist;
import com.nori.tc.db.domain.common.eqp.LogLevel;
import com.nori.tc.db.domain.jar.TcJarBusiness;
import com.nori.tc.db.domain.jar.TcJarGateway;
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
 * <p>비교 대상은 스냅샷 13 필드 전부다. 배치가 채우지 않는 필드가 하나라도 있으면
 * {@code GET /api/eqp/manage-batch} 응답이 단건 {@code GET /api/eqp/&#123;eqpId&#125;/manage} 와 조용히 갈린다.</p>
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
        final TcEqpPortStatus ps3 = new TcEqpPortStatus(103L, 1L, "P3", null, "DOWN", null, null, null, TS);
        // param: version desc, name asc(ignoreCase) 정렬 확인용으로 일부러 뒤섞어 제공
        final TcEqpParam pA = param(201L, 1L, "ALPHA", "V1");
        final TcEqpParam pB = param(202L, 1L, "beta", "V2");
        final TcEqpParam pC = param(203L, 1L, "GAMMA", "V2");
        // paramVersion: version desc 정렬 확인용으로 일부러 오름차순으로 제공
        final TcEqpParamVersion pv1 = paramVersion(301L, 1L, "V1");
        final TcEqpParamVersion pv2 = paramVersion(302L, 1L, "V2");
        final TcEqpStateHist conn1 = connHist(401L, 1L, "REMOTE");
        final TcEqpStateHist conn2 = connHist(402L, 2L, "OFFLINE");
        final TcJarGateway gwJar = new TcJarGateway(901L, "gw-1.0.jar", null, TS, TS, "SYSTEM", "SYSTEM");
        final TcJarBusiness bizJar = new TcJarBusiness(902L, "biz-1.0.jar", null, TS, TS, "SYSTEM", "SYSTEM");

        // --- 단건 경로 stubs ---
        when(eqpStore.findByEqpId("E1")).thenReturn(Optional.of(e1));
        when(modelStore.findByModelVersionKey(10L)).thenReturn(Optional.of(m1));
        when(eqpHsmsStore.findByEqpKey(1L)).thenReturn(Optional.of(hsms1));
        when(eqpLogStore.findByEqpKey(1L)).thenReturn(Optional.of(log1));
        when(eqpStateStore.findByEqpKey(1L)).thenReturn(Optional.of(state1));
        // 단건 store 는 portId ASC 로 읽는다(JPA cb.asc(portId)).
        when(eqpPortStatusStore.findAllByEqpKey(eq(1L), any(PageRequest.class))).thenReturn(List.of(ps1, ps2, ps3));
        when(eqpParamStore.findAllByEqpKey(eq(1L), any(PageRequest.class))).thenReturn(List.of(pA, pB, pC));
        when(eqpParamVersionStore.findAllByEqpKey(eq(1L), any(PageRequest.class))).thenReturn(List.of(pv1, pv2));
        when(eqpStateHistStore.findAllByEqpKey(eq(1L), any(PageRequest.class))).thenReturn(List.of(conn1));

        when(eqpStore.findByEqpId("E2")).thenReturn(Optional.of(e2));
        when(modelStore.findByModelVersionKey(20L)).thenReturn(Optional.of(m2));
        when(eqpSocketStore.findByEqpKey(2L)).thenReturn(Optional.of(socket2));
        when(eqpStateStore.findByEqpKey(2L)).thenReturn(Optional.of(state2));
        when(eqpStateHistStore.findAllByEqpKey(eq(2L), any(PageRequest.class))).thenReturn(List.of(conn2));

        when(jarGatewayStore.findByGatewayJarKey(901L)).thenReturn(Optional.of(gwJar));
        when(jarBusinessStore.findByBusinessJarKey(902L)).thenReturn(Optional.of(bizJar));

        // --- 배치 경로 stubs ---
        when(eqpStore.findAll(any(PageRequest.class))).thenReturn(List.of(e1, e2));
        when(modelStore.findAll(any(PageRequest.class))).thenReturn(List.of(m1, m2));
        when(jarGatewayStore.findAll(any(PageRequest.class))).thenReturn(List.of(gwJar));
        when(jarBusinessStore.findAll(any(PageRequest.class))).thenReturn(List.of(bizJar));
        final List<Long> keys = List.of(1L, 2L);
        when(eqpHsmsStore.findAllByEqpKeyIn(keys)).thenReturn(List.of(hsms1));
        when(eqpSocketStore.findAllByEqpKeyIn(keys)).thenReturn(List.of(socket2));
        when(eqpPlcStore.findAllByEqpKeyIn(keys)).thenReturn(List.of());
        when(eqpLogStore.findAllByEqpKeyIn(keys)).thenReturn(List.of(log1));
        when(eqpStateStore.findAllByEqpKeyIn(keys)).thenReturn(List.of(state1, state2));
        // 배치 store 는 ORDER BY 가 없어 DB 가 주는 임의 순서다 — 일부러 뒤섞어 정렬 책임을 조립부에 묻는다.
        when(eqpPortStatusStore.findAllByEqpKeyIn(keys)).thenReturn(List.of(ps2, ps3, ps1));
        when(eqpParamStore.findAllByEqpKeyIn(keys)).thenReturn(List.of(pA, pB, pC));
        when(eqpParamVersionStore.findAllByEqpKeyIn(keys)).thenReturn(List.of(pv1, pv2));
        when(eqpStateHistStore.findLatestConnStateByEqpKeyIn(keys)).thenReturn(List.of(conn1, conn2));

        final Map<String, EqpManagementSnapshot> batch = dbSupport.loadSnapshotsByEqpIds(List.of("E1", "E2"));

        assertAllFieldsEqual(dbSupport.loadSnapshotByEqpId("E1").orElseThrow(), batch.get("E1"));
        assertAllFieldsEqual(dbSupport.loadSnapshotByEqpId("E2").orElseThrow(), batch.get("E2"));

        // 단건과 같은 값이면서 stub 이 실제로 물렸는지 — null == null 로 통과하는 공허한 비교를 막는다.
        assertEquals("REMOTE", batch.get("E1").connectionState());
        assertEquals("MODEL_A", batch.get("E1").model().modelName());
        assertEquals("gw-1.0.jar", batch.get("E1").gatewayJar().jarFileName());
        assertEquals("biz-1.0.jar", batch.get("E1").businessJar().jarFileName());
        assertEquals(
                List.of("V2", "V1"),
                batch.get("E1").paramVersionMetas().stream().map(v -> v.paramVersion()).toList()
        );
        assertEquals(
                List.of("P1", "P2", "P3"),
                batch.get("E1").portStatuses().stream().map(p -> p.portId()).toList(),
                "배치 store 가 순서를 보장하지 않으므로 조립부가 portId ASC 로 맞춰야 한다."
        );

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

    private static void assertAllFieldsEqual(final EqpManagementSnapshot single, final EqpManagementSnapshot batch) {
        assertNotNull(batch);
        assertEquals(single.eqp(), batch.eqp());
        assertEquals(single.model(), batch.model());
        assertEquals(single.hsms(), batch.hsms());
        assertEquals(single.socket(), batch.socket());
        assertEquals(single.plc(), batch.plc());
        assertEquals(single.logPolicy(), batch.logPolicy());
        assertEquals(single.runtimeState(), batch.runtimeState());
        assertEquals(single.connectionState(), batch.connectionState());
        assertEquals(single.portStatuses(), batch.portStatuses());
        assertEquals(single.params(), batch.params());
        assertEquals(single.paramVersionMetas(), batch.paramVersionMetas());
        assertEquals(single.gatewayJar(), batch.gatewayJar());
        assertEquals(single.businessJar(), batch.businessJar());
    }

    /**
     * lineKey 는 검증 대상이 아니라 1 로 고정하고, modelVersionKey / jar key 는 인자로 받습니다.
     *
     * <p>이전 버전은 3 번째 인자를 modelVersionKey 로 이름 붙여 놓고 실제로는 lineKey 자리에 넘겼고,
     * modelVersionKey 는 전 설비가 301L 고정이었습니다. 그래서 {@code findByModelVersionKey(10L)} stub 이
     * 물리지 않아 단건도 배치도 model 이 null 이었고, model 동등성 단언이 null == null 로 통과했습니다.</p>
     */
    private static TcEqp eqp(final long eqpKey, final String eqpId, final ProtocolType iface, final long modelVersionKey) {
        return new TcEqp(
                eqpKey, eqpId, 1L, iface, "ACTIVE", false, 1, "127.0.0.1", 5000,
                modelVersionKey, null, 901L, 902L, true, TS, TS, "SYSTEM", "SYSTEM");
    }

    private static TcEqpParamVersion paramVersion(final long key, final long eqpKey, final String version) {
        return new TcEqpParamVersion(key, eqpKey, version, "desc-" + version, TS, TS, "SYSTEM", "SYSTEM");
    }

    private static TcEqpStateHist connHist(final long key, final long eqpKey, final String toState) {
        return new TcEqpStateHist(key, eqpKey, EqpStateType.CONN, null, toState, TS, "REASON", null);
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
