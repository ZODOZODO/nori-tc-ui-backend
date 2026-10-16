package com.nori.tc.ui.adapters.redis.coordination;

import com.nori.tc.common.coordination.store.FencedLeaseRedisStore;
import com.nori.tc.common.coordination.store.FencedLeaseRedisStore.LeaseSnapshot;
import com.nori.common.shard.SharedShardGroup;
import com.nori.tc.common.shard.SharedShardGroupProperties;
import com.nori.tc.ui.core.port.redis.GatewayShardLeasePort.GatewayShardLeaseView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoordinatedGatewayShardLeaseAdapterTest {

    private static final String GROUP_1_LEASE_KEY = "tc:coord:v1:gateway:shard:group-1:lease";
    private static final String GROUP_2_LEASE_KEY = "tc:coord:v1:gateway:shard:group-2:lease";

    @Mock
    private FencedLeaseRedisStore leaseStore;

    private SharedShardGroupProperties shardGroupProperties;

    /** 레거시 raw String 키 조회원이 답하는 내용 — 테스트마다 갈아 끼운다. */
    private List<GatewayShardLeaseView> legacyLeases;

    @BeforeEach
    void setUp() {
        shardGroupProperties = new SharedShardGroupProperties();
        legacyLeases = List.of();
    }

    private CoordinatedGatewayShardLeaseAdapter adapter() {
        return new CoordinatedGatewayShardLeaseAdapter(
                leaseStore, shardGroupProperties, () -> legacyLeases);
    }

    private static SharedShardGroup group(final String id, final List<Integer> partitions) {
        final SharedShardGroup group = new SharedShardGroup();
        group.setId(id);
        group.setOwnedPartitions(partitions);
        return group;
    }

    @Test
    @DisplayName("조정 lease 키에서 group 별 소유 session 을 정의 순서대로 읽는다")
    void readsOwnerSessionPerGroupFromCoordinationKey() {
        shardGroupProperties.setShardGroups(List.of(
                group("group-1", List.of(0, 1, 2)),
                group("group-2", List.of(3, 4, 5))
        ));
        when(leaseStore.currentLease(GROUP_1_LEASE_KEY))
                .thenReturn(Optional.of(new LeaseSnapshot("tc-gateway:uid-a:boot-a", 7L, 100L, null)));
        when(leaseStore.currentLease(GROUP_2_LEASE_KEY))
                .thenReturn(Optional.empty());

        final List<GatewayShardLeaseView> leases = adapter().findAllLeases();

        assertEquals(2, leases.size());
        assertEquals("group-1", leases.get(0).groupId());
        assertEquals(List.of(0, 1, 2), leases.get(0).ownedPartitions());
        assertEquals("tc-gateway:uid-a:boot-a", leases.get(0).ownerInstanceId());
        assertEquals("group-2", leases.get(1).groupId());
        assertNull(leases.get(1).ownerInstanceId(), "미점유 group 은 소유자가 없다");
    }

    @Test
    @DisplayName("epoch 0 이나 빈 sessionId 는 점유로 보지 않는다")
    void doesNotTreatZeroEpochOrBlankSessionAsOwned() {
        shardGroupProperties.setShardGroups(List.of(
                group("group-1", List.of(0)),
                group("group-2", List.of(1))
        ));
        when(leaseStore.currentLease(GROUP_1_LEASE_KEY))
                .thenReturn(Optional.of(new LeaseSnapshot("tc-gateway:uid-a:boot-a", 0L, 100L, null)));
        when(leaseStore.currentLease(GROUP_2_LEASE_KEY))
                .thenReturn(Optional.of(new LeaseSnapshot("   ", 3L, 100L, null)));

        final List<GatewayShardLeaseView> leases = adapter().findAllLeases();

        assertNull(leases.get(0).ownerInstanceId());
        assertNull(leases.get(1).ownerInstanceId());
    }

    @Test
    @DisplayName("조정 lease 가 없는 group 은 레거시 raw 키의 점유를 쓴다")
    void fallsBackToLegacyKeyWhenCoordinationLeaseIsAbsent() {
        /*
         * lease 기록 스키마는 Gateway 쪽 버전이 정한다 — 라이브 실측(2026-08-26)에서 Redis 에는
         * tc:coord:v1:* 가 0 건이고 tc:gateway:lease:group-1/2 만 있었다. 조정 키만 읽으면 그 상태에서
         * 전 group 이 미점유로 보인다.
         */
        shardGroupProperties.setShardGroups(List.of(
                group("group-1", List.of(0, 1, 2)),
                group("group-2", List.of(3, 4, 5))
        ));
        when(leaseStore.currentLease(GROUP_1_LEASE_KEY)).thenReturn(Optional.empty());
        when(leaseStore.currentLease(GROUP_2_LEASE_KEY)).thenReturn(Optional.empty());
        legacyLeases = List.of(
                new GatewayShardLeaseView("group-1", List.of(0, 1, 2), "tc-gateway-5bdc8cdc4d-g8zs5-c7e1699b"),
                new GatewayShardLeaseView("group-2", List.of(3, 4, 5), null)
        );

        final List<GatewayShardLeaseView> leases = adapter().findAllLeases();

        assertEquals("tc-gateway-5bdc8cdc4d-g8zs5-c7e1699b", leases.get(0).ownerInstanceId());
        assertNull(leases.get(1).ownerInstanceId(), "레거시 키에도 값이 없으면 미점유다");
    }

    @Test
    @DisplayName("조정 lease 가 있으면 레거시 잔재 값이 그것을 덮지 않는다")
    void coordinationLeaseWinsOverLegacyResidue() {
        // 조정 Gateway 로 넘어간 뒤에도 레거시 키는 TTL 이 남아 있을 수 있다 — 옛 값이 이기면 안 된다.
        shardGroupProperties.setShardGroups(List.of(
                group("group-1", List.of(0)),
                group("group-2", List.of(1))
        ));
        when(leaseStore.currentLease(GROUP_1_LEASE_KEY))
                .thenReturn(Optional.of(new LeaseSnapshot("tc-gateway:uid-a:boot-a", 7L, 100L, null)));
        when(leaseStore.currentLease(GROUP_2_LEASE_KEY)).thenReturn(Optional.empty());
        legacyLeases = List.of(
                new GatewayShardLeaseView("group-1", List.of(0), "stale-legacy-owner"),
                new GatewayShardLeaseView("group-2", List.of(1), null)
        );

        final List<GatewayShardLeaseView> leases = adapter().findAllLeases();

        assertEquals("tc-gateway:uid-a:boot-a", leases.get(0).ownerInstanceId());
        assertNull(leases.get(1).ownerInstanceId());
    }

    @Test
    @DisplayName("shard-group 매핑이 비면 빈 목록이다")
    void returnsEmptyWhenNoShardGroupIsDefined() {
        assertTrue(adapter().findAllLeases().isEmpty());
    }
}
