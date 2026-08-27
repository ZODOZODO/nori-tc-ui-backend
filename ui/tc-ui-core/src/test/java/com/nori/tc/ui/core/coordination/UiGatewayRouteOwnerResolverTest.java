package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
import com.nori.tc.ui.core.port.messaging.UiGatewayEqpRoutePartitionLookupPort;
import com.nori.tc.ui.core.port.redis.GatewayShardLeasePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gateway 참여자 판정이 <b>명령이 실제로 도달하는 Pod</b>을 고르는지 고정합니다(§11.3/§12.2).
 *
 * <p>partition 결정 출처가 명령 발행 경로와 어긋나면 "명령은 A Pod, 증적 요구는 B Pod" 가 되어 barrier 가
 * 영구히 닫히지 않습니다. 그래서 descriptor 우선 → DB 조회 순서를 여기서 못 박습니다.</p>
 */
class UiGatewayRouteOwnerResolverTest {

    private static final String OWNER_GROUP_1 = "tc-gateway:pod-g1:boot-1";
    private static final String OWNER_GROUP_2 = "tc-gateway:pod-g2:boot-2";

    @Test
    @DisplayName("descriptor 의 routePartition 소유자를 참여자로 고른다 — DB 조회는 하지 않는다")
    void descriptorPartitionWins() {
        final UiGatewayRouteOwnerResolver resolver = resolver(
                eqpId -> {
                    throw new AssertionError("descriptor 에 값이 있으면 DB 를 조회하면 안 된다.");
                },
                leases());

        assertEquals(List.of(OWNER_GROUP_2), resolver.ownerSessionIds(manifest(4)));
    }

    @Test
    @DisplayName("descriptor 에 routePartition 이 없으면 DB 의 route_partition 을 쓴다")
    void fallsBackToDatabasePartition() {
        final UiGatewayRouteOwnerResolver resolver = resolver(eqpId -> Optional.of(1), leases());

        assertEquals(List.of(OWNER_GROUP_1), resolver.ownerSessionIds(manifest(null)));
    }

    @Test
    @DisplayName("route partition 이동은 옛 owner 와 새 owner 를 모두 참여자로 낸다(§12.5)")
    void routeMoveIncludesBothOwners() {
        final UiGatewayRouteOwnerResolver resolver = resolver(
                UiGatewayEqpRoutePartitionLookupPort.noop(), leases());

        /*
         * 새 owner 만 고정하면 옛 owner 에게 release 명령이 가지 않아 아무도 release 하지 못하고,
         * 새 owner 는 release proof 를 기다리다 EQP_ROUTE_MOVE_NOT_RELEASED 로 abort 된다(운영 실측).
         */
        assertEquals(List.of(OWNER_GROUP_1, OWNER_GROUP_2),
                resolver.ownerSessionIds(movingManifest(0, 4)),
                "옛 owner(release) → 새 owner(bind) 순으로 둘 다 참여자여야 한다.");
    }

    @Test
    @DisplayName("같은 shard group 안에서 partition 만 바뀌면 참여자는 하나다")
    void intraGroupMoveHasSingleOwner() {
        final UiGatewayRouteOwnerResolver resolver = resolver(
                UiGatewayEqpRoutePartitionLookupPort.noop(), leases());

        assertEquals(List.of(OWNER_GROUP_2), resolver.ownerSessionIds(movingManifest(3, 4)),
                "같은 Pod 이 양쪽을 소유하면 release/bind 가 한 Pod 안에서 끝난다.");
    }

    @Test
    @DisplayName("이동의 옛 owner 를 특정할 수 없으면 아무도 참여자로 내지 않는다")
    void unresolvablePreviousOwnerYieldsNoParticipant() {
        final UiGatewayRouteOwnerResolver resolver = resolver(
                UiGatewayEqpRoutePartitionLookupPort.noop(),
                List.of(new GatewayShardLeasePort.GatewayShardLeaseView(
                        "group-2", List.of(3, 4, 5), OWNER_GROUP_2)));

        /*
         * 새 owner 만 내면 release 없는 bind 를 허용하게 되어 double ownership 이 열린다.
         * 특정 불가는 fail-closed 로 대기해야 한다.
         */
        assertTrue(resolver.ownerSessionIds(movingManifest(0, 4)).isEmpty(),
                "옛 owner 미특정 상태로 이동을 진행시키면 double ownership 이 열린다.");
    }

    @Test
    @DisplayName("이전 상태가 tombstone 이면 이동이 아니다 — 새 owner 하나만 낸다")
    void tombstonePreviousIsNotAMove() {
        final UiGatewayRouteOwnerResolver resolver = resolver(
                UiGatewayEqpRoutePartitionLookupPort.noop(), leases());

        final UiOperationManifestView created = new UiOperationManifestView(
                "EQP_CREATE", "EQP001", "tester", null, "SOCKET", true,
                List.of("equipment:EQP001"),
                Map.of("equipment:EQP001", "{\"schemaVersion\":2,\"eqpId\":\"EQP001\",\"routePartition\":4}"),
                Map.of("equipment:EQP001", 0L),
                null, null,
                Map.of("equipment:EQP001",
                        "{\"schemaVersion\":2,\"eqpId\":\"EQP001\",\"deleted\":true,\"routePartition\":0}"),
                null, null);

        assertEquals(List.of(OWNER_GROUP_2), resolver.ownerSessionIds(created),
                "생성은 옛 owner 가 없다 — 없는 owner 를 기다리면 아무도 release 할 수 없다.");
    }

    @Test
    @DisplayName("해당 shard group 을 아무도 점유하지 않았으면 참여자를 특정하지 않는다")
    void unownedGroupYieldsNoParticipant() {
        final UiGatewayRouteOwnerResolver resolver = resolver(
                UiGatewayEqpRoutePartitionLookupPort.noop(),
                List.of(new GatewayShardLeasePort.GatewayShardLeaseView("group-2", List.of(3, 4, 5), null)));

        assertTrue(resolver.ownerSessionIds(manifest(4)).isEmpty(),
                "미점유 group 의 설비를 소유자 없이 진행시키면 Gateway 증적 없는 PASS 가 된다.");
    }

    @Test
    @DisplayName("어느 group 에도 속하지 않는 partition 이면 참여자를 특정하지 않는다")
    void unknownPartitionYieldsNoParticipant() {
        final UiGatewayRouteOwnerResolver resolver =
                resolver(UiGatewayEqpRoutePartitionLookupPort.noop(), leases());

        assertTrue(resolver.ownerSessionIds(manifest(99)).isEmpty());
    }

    @Test
    @DisplayName("descriptor 와 DB 어디에도 partition 이 없으면 참여자를 특정하지 않는다")
    void missingPartitionYieldsNoParticipant() {
        final UiGatewayRouteOwnerResolver resolver =
                resolver(UiGatewayEqpRoutePartitionLookupPort.noop(), leases());

        assertTrue(resolver.ownerSessionIds(manifest(null)).isEmpty());
    }

    private static UiGatewayRouteOwnerResolver resolver(
            final UiGatewayEqpRoutePartitionLookupPort lookupPort,
            final List<GatewayShardLeasePort.GatewayShardLeaseView> leases
    ) {
        return new UiGatewayRouteOwnerResolver(
                new UiRuntimeDescriptorReader(new ObjectMapper()), lookupPort, () -> leases);
    }

    private static List<GatewayShardLeasePort.GatewayShardLeaseView> leases() {
        return List.of(
                new GatewayShardLeasePort.GatewayShardLeaseView(
                        "group-1", List.of(0, 1, 2), OWNER_GROUP_1),
                new GatewayShardLeasePort.GatewayShardLeaseView(
                        "group-2", List.of(3, 4, 5), OWNER_GROUP_2));
    }

    /**
     * routePartition 이 있는(또는 없는) descriptor 를 담은 manifest 를 만듭니다.
     */
    private static UiOperationManifestView manifest(final Integer routePartition) {
        final String descriptorJson = routePartition == null
                ? "{\"schemaVersion\":2,\"eqpId\":\"EQP001\"}"
                : "{\"schemaVersion\":2,\"eqpId\":\"EQP001\",\"routePartition\":" + routePartition + "}";
        return new UiOperationManifestView(
                "EQP_UPDATE", "EQP001", "tester", null, "SOCKET", true,
                List.of("equipment:EQP001"),
                Map.of("equipment:EQP001", descriptorJson),
                Map.of("equipment:EQP001", 0L),
                null, null, Map.of(), null, null);
    }

    /**
     * 옛 partition 에서 새 partition 으로 옮기는 manifest 를 만듭니다.
     */
    private static UiOperationManifestView movingManifest(final int previous, final int target) {
        return new UiOperationManifestView(
                "EQP_UPDATE", "EQP001", "tester", null, "SOCKET", true,
                List.of("equipment:EQP001"),
                Map.of("equipment:EQP001",
                        "{\"schemaVersion\":2,\"eqpId\":\"EQP001\",\"routePartition\":" + target + "}"),
                Map.of("equipment:EQP001", 1L),
                null, null,
                Map.of("equipment:EQP001",
                        "{\"schemaVersion\":2,\"eqpId\":\"EQP001\",\"routePartition\":" + previous + "}"),
                null, null);
    }
}
