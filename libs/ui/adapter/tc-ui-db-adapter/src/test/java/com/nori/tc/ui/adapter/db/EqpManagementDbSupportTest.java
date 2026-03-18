package com.nori.tc.ui.adapter.db;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link EqpManagementDbSupport}의 jar 최신 선택 규칙을 검증합니다.
 */
class EqpManagementDbSupportTest {

    @Test
    @DisplayName("Gateway jar filename 조회는 updatedAt이 가장 최신인 row를 선택합니다")
    void findLatestGatewayJarByFileNameSelectsNewestRow() {
        final Fixture fixture = new Fixture();
        final TcEqp eqp1 = eqp(1L, "EQP-01");
        final TcEqp eqp2 = eqp(2L, "EQP-02");
        final TcJarGateway olderJar = new TcJarGateway(
                1L,
                "gateway-main.jar",
                new byte[]{1},
                OffsetDateTime.parse("2026-03-10T10:15:30+09:00"),
                OffsetDateTime.parse("2026-03-10T10:15:30+09:00"),
                "SYSTEM",
                "SYSTEM"
        );
        final TcJarGateway latestJar = new TcJarGateway(
                2L,
                "gateway-main.jar",
                new byte[]{2},
                OffsetDateTime.parse("2026-03-11T10:15:30+09:00"),
                OffsetDateTime.parse("2026-03-11T11:15:30+09:00"),
                "SYSTEM",
                "SYSTEM"
        );

        when(fixture.eqpStore.findAll(any())).thenReturn(List.of(eqp1, eqp2));
        when(fixture.jarGatewayStore.findByEqpKey(1L)).thenReturn(Optional.of(olderJar));
        when(fixture.jarGatewayStore.findByEqpKey(2L)).thenReturn(Optional.of(latestJar));

        final Optional<TcJarGateway> selected = fixture.dbSupport.findLatestGatewayJarByFileName("gateway-main.jar");

        assertEquals(true, selected.isPresent());
        assertSame(latestJar, selected.get());
    }

    @Test
    @DisplayName("Business jar filename 조회는 updatedAt이 가장 최신인 row를 선택합니다")
    void findLatestBusinessJarByFileNameSelectsNewestRow() {
        final Fixture fixture = new Fixture();
        final TcEqp eqp1 = eqp(11L, "EQP-11");
        final TcEqp eqp2 = eqp(12L, "EQP-12");
        final TcJarBusiness olderJar = new TcJarBusiness(
                11L,
                "business-main.jar",
                new byte[]{3},
                OffsetDateTime.parse("2026-03-10T09:15:30+09:00"),
                OffsetDateTime.parse("2026-03-10T09:15:30+09:00"),
                "SYSTEM",
                "SYSTEM"
        );
        final TcJarBusiness latestJar = new TcJarBusiness(
                12L,
                "business-main.jar",
                new byte[]{4},
                OffsetDateTime.parse("2026-03-11T09:15:30+09:00"),
                OffsetDateTime.parse("2026-03-11T12:15:30+09:00"),
                "SYSTEM",
                "SYSTEM"
        );

        when(fixture.eqpStore.findAll(any())).thenReturn(List.of(eqp1, eqp2));
        when(fixture.jarBusinessStore.findByEqpKey(11L)).thenReturn(Optional.of(olderJar));
        when(fixture.jarBusinessStore.findByEqpKey(12L)).thenReturn(Optional.of(latestJar));

        final Optional<TcJarBusiness> selected = fixture.dbSupport.findLatestBusinessJarByFileName("business-main.jar");

        assertEquals(true, selected.isPresent());
        assertSame(latestJar, selected.get());
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
                socketProtocolTypeStore
        );
    }

    private static TcEqp eqp(final long eqpKey, final String eqpId) {
        final OffsetDateTime now = OffsetDateTime.parse("2026-03-11T10:15:30+09:00");
        return new TcEqp(
                eqpKey,
                eqpId,
                ProtocolType.SECS,
                "ACTIVE",
                false,
                1,
                "127.0.0.1",
                5000,
                101L,
                null,
                true,
                now,
                now,
                "SYSTEM",
                "SYSTEM"
        );
    }
}
