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

    private static final String OWNER_GROUP_2 = "tc-gateway:pod-g2:boot-2";

    @Test
    @DisplayName("descriptor 의 routePartition 소유자를 참여자로 고른다 — DB 조회는 하지 않는다")
    void descriptorPartitionWins() {
        final UiGatewayRouteOwnerResolver resolver = resolver(
                eqpId -> {
                    throw new AssertionError("descriptor 에 값이 있으면 DB 를 조회하면 안 된다.");
                },
                leases());

        assertEquals(Optional.of(OWNER_GROUP_2), resolver.ownerSessionId(manifest(4)));
    }

    @Test
    @DisplayName("descriptor 에 routePartition 이 없으면 DB 의 route_partition 을 쓴다")
    void fallsBackToDatabasePartition() {
        final UiGatewayRouteOwnerResolver resolver = resolver(eqpId -> Optional.of(1), leases());

        assertEquals(Optional.of("tc-gateway:pod-g1:boot-1"), resolver.ownerSessionId(manifest(null)));
    }

    @Test
    @DisplayName("해당 shard group 을 아무도 점유하지 않았으면 참여자를 특정하지 않는다")
    void unownedGroupYieldsNoParticipant() {
        final UiGatewayRouteOwnerResolver resolver = resolver(
                UiGatewayEqpRoutePartitionLookupPort.noop(),
                List.of(new GatewayShardLeasePort.GatewayShardLeaseView("group-2", List.of(3, 4, 5), null)));

        assertTrue(resolver.ownerSessionId(manifest(4)).isEmpty(),
                "미점유 group 의 설비를 소유자 없이 진행시키면 Gateway 증적 없는 PASS 가 된다.");
    }

    @Test
    @DisplayName("어느 group 에도 속하지 않는 partition 이면 참여자를 특정하지 않는다")
    void unknownPartitionYieldsNoParticipant() {
        final UiGatewayRouteOwnerResolver resolver =
                resolver(UiGatewayEqpRoutePartitionLookupPort.noop(), leases());

        assertTrue(resolver.ownerSessionId(manifest(99)).isEmpty());
    }

    @Test
    @DisplayName("descriptor 와 DB 어디에도 partition 이 없으면 참여자를 특정하지 않는다")
    void missingPartitionYieldsNoParticipant() {
        final UiGatewayRouteOwnerResolver resolver =
                resolver(UiGatewayEqpRoutePartitionLookupPort.noop(), leases());

        assertTrue(resolver.ownerSessionId(manifest(null)).isEmpty());
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
                        "group-1", List.of(0, 1, 2), "tc-gateway:pod-g1:boot-1"),
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
}
