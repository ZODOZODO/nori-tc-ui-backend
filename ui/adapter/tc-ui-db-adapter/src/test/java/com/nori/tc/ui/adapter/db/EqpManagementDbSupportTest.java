package com.nori.tc.ui.adapter.db;

import com.nori.tc.common.shard.ShardGroupResolver;
import com.nori.tc.db.core.eqp.store.TcEqpHsmsStore;
import com.nori.tc.db.core.eqp.store.TcEqpLogStore;
import com.nori.tc.db.core.eqp.store.TcEqpParamStore;
import com.nori.tc.db.core.eqp.store.TcEqpParamVersionStore;
import com.nori.tc.db.core.eqp.store.TcEqpPortStatusStore;
import com.nori.tc.db.core.eqp.store.TcEqpSocketProtocolTypeStore;
import com.nori.tc.db.core.eqp.store.TcEqpSocketStore;
import com.nori.tc.db.core.eqp.store.TcEqpStateHistStore;
import com.nori.tc.db.core.eqp.store.TcEqpStateStore;
import com.nori.tc.db.core.eqp.store.TcEqpStore;
import com.nori.tc.db.core.jar.store.TcJarBusinessStore;
import com.nori.tc.db.core.jar.store.TcJarGatewayStore;
import com.nori.tc.db.core.model.store.TcModelStore;
import com.nori.tc.db.domain.common.model.ProtocolType;
import com.nori.tc.db.domain.eqp.TcEqp;
import com.nori.tc.db.domain.jar.TcJarBusiness;
import com.nori.tc.db.domain.jar.TcJarGateway;
import com.nori.tc.ui.adapter.db.mapper.UiEqpDbMapper;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EqpManagementDbSupport}의 EQP-JAR 참조 조립 규칙을 검증합니다.
 */
class EqpManagementDbSupportTest {

    @Test
    @DisplayName("관리 스냅샷은 tc_eqp가 선택한 jar key로 gateway/business jar를 조회합니다")
    void loadSnapshotByEqpIdUsesSelectedJarKeys() {
        final Fixture fixture = new Fixture();
        final TcEqp eqp = eqp(1L, "EQP-01", 101L, 201L);
        final TcJarGateway gatewayJar = new TcJarGateway(
                101L,
                "gateway-main.jar",
                new byte[]{1},
                OffsetDateTime.parse("2026-03-10T10:15:30+09:00"),
                OffsetDateTime.parse("2026-03-10T10:15:30+09:00"),
                "SYSTEM",
                "SYSTEM"
        );
        final TcJarBusiness businessJar = new TcJarBusiness(
                201L,
                "business-main.jar",
                new byte[]{2},
                OffsetDateTime.parse("2026-03-10T11:15:30+09:00"),
                OffsetDateTime.parse("2026-03-10T11:15:30+09:00"),
                "SYSTEM",
                "SYSTEM"
        );

        when(fixture.eqpStore.findByEqpId("EQP-01")).thenReturn(Optional.of(eqp));
        when(fixture.jarGatewayStore.findByGatewayJarKey(101L)).thenReturn(Optional.of(gatewayJar));
        when(fixture.jarBusinessStore.findByBusinessJarKey(201L)).thenReturn(Optional.of(businessJar));

        final Optional<EqpManagementSnapshot> loaded = fixture.dbSupport.loadSnapshotByEqpId("EQP-01");

        assertTrue(loaded.isPresent());
        assertEquals(UiEqpDbMapper.toDomain(gatewayJar), loaded.get().gatewayJar());
        assertEquals(UiEqpDbMapper.toDomain(businessJar), loaded.get().businessJar());
        verify(fixture.jarGatewayStore).findByGatewayJarKey(101L);
        verify(fixture.jarBusinessStore).findByBusinessJarKey(201L);
    }

    @Test
    @DisplayName("관리 스냅샷은 선택된 jar key가 없으면 jar 조회를 생략합니다")
    void loadSnapshotByEqpIdSkipsJarLookupWhenNoJarSelected() {
        final Fixture fixture = new Fixture();
        when(fixture.eqpStore.findByEqpId("EQP-02")).thenReturn(Optional.of(eqp(2L, "EQP-02", null, null)));

        final Optional<EqpManagementSnapshot> loaded = fixture.dbSupport.loadSnapshotByEqpId("EQP-02");

        assertTrue(loaded.isPresent());
        assertNull(loaded.get().gatewayJar());
        assertNull(loaded.get().businessJar());
    }

    /**
     * 공통 mock 의존성을 묶는 테스트 fixture입니다.
     */
    private static final class Fixture {

        private final TcEqpStore eqpStore = mock(TcEqpStore.class);
        private final TcEqpHsmsStore eqpHsmsStore = mock(TcEqpHsmsStore.class);
        private final TcEqpSocketStore eqpSocketStore = mock(TcEqpSocketStore.class);
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
                eqpStore,
                eqpHsmsStore,
                eqpSocketStore,
                eqpLogStore,
                eqpStateStore,
                eqpStateHistStore,
                eqpPortStatusStore,
                eqpParamStore,
                eqpParamVersionStore,
                jarGatewayStore,
                jarBusinessStore,
                modelStore,
                socketProtocolTypeStore,
                shardGroupResolver
        );
    }

    private static TcEqp eqp(
            final long eqpKey,
            final String eqpId,
            final Long gatewayJarKey,
            final Long businessJarKey
    ) {
        final OffsetDateTime now = OffsetDateTime.parse("2026-03-11T10:15:30+09:00");
        return new TcEqp(
                eqpKey,
                eqpId,
                1L,
                ProtocolType.SECS,
                "ACTIVE",
                false,
                1,
                "127.0.0.1",
                5000,
                301L,
                null,
                gatewayJarKey,
                businessJarKey,
                true,
                now,
                now,
                "SYSTEM",
                "SYSTEM"
        );
    }
}
