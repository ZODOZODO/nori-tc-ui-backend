package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
import com.nori.tc.ui.core.coordination.port.UiGatewayParticipantResolver;
import com.nori.tc.ui.core.port.messaging.UiGatewayEqpRoutePartitionLookupPort;
import com.nori.tc.ui.core.port.redis.GatewayShardLeasePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * route partition 의 shard lease 소유자를 Gateway 참여자로 정합니다(§11.3/§12.2).
 *
 * <p>partition 결정 순서는 <b>명령 발행 경로와 같아야</b> 합니다 — 발행은 manifest descriptor 의
 * {@code routePartition} 을 먼저 쓰고, 그 값이 없으면(변경 대상이 아니면 null) {@code tc_eqp.route_partition}
 * 조회로 떨어집니다. 참여자 판정이 다른 출처를 쓰면 "명령은 A Pod 에, 증적 요구는 B Pod 에" 가 되어
 * barrier 가 닫히지 않습니다.</p>
 *
 * <p>lease 의 {@code ownerInstanceId} 는 조정 스키마에서 membership session id 와 같은 문자열입니다.
 * 레거시 스키마(조정을 쓰지 않는 Gateway)는 session 식별자가 아닌 불투명 토큰을 돌려주므로, 호출자는
 * 이 값이 <b>ACTIVE_SYNCED membership 집합에 실제로 있는지</b> 확인한 뒤 참여자로 고정해야 합니다.</p>
 */
public final class UiGatewayRouteOwnerResolver implements UiGatewayParticipantResolver {

    private static final Logger log = LoggerFactory.getLogger(UiGatewayRouteOwnerResolver.class);

    private final UiRuntimeDescriptorReader descriptorReader;
    private final UiGatewayEqpRoutePartitionLookupPort routePartitionLookupPort;
    private final GatewayShardLeasePort shardLeasePort;

    /**
     * resolver 를 만듭니다.
     *
     * @param descriptorReader manifest descriptor 해석기
     * @param routePartitionLookupPort eqpId -> route_partition 조회 포트
     * @param shardLeasePort shard group lease 점유 현황 조회 포트
     */
    public UiGatewayRouteOwnerResolver(
            final UiRuntimeDescriptorReader descriptorReader,
            final UiGatewayEqpRoutePartitionLookupPort routePartitionLookupPort,
            final GatewayShardLeasePort shardLeasePort
    ) {
        this.descriptorReader = Objects.requireNonNull(descriptorReader, "descriptorReader is null");
        this.routePartitionLookupPort =
                Objects.requireNonNull(routePartitionLookupPort, "routePartitionLookupPort is null");
        this.shardLeasePort = Objects.requireNonNull(shardLeasePort, "shardLeasePort is null");
    }

    @Override
    public Optional<String> ownerSessionId(final UiOperationManifestView manifest) {
        Objects.requireNonNull(manifest, "manifest is null");
        final Optional<Integer> partition = routePartitionOf(manifest);
        if (partition.isEmpty()) {
            log.warn("UI_GATEWAY_ROUTE_PARTITION_UNRESOLVED. eqpId={}, eventType={}",
                    manifest.eqpId(), manifest.eventType());
            return Optional.empty();
        }

        final int routePartition = partition.get();
        for (final GatewayShardLeasePort.GatewayShardLeaseView lease : shardLeasePort.findAllLeases()) {
            if (!lease.ownedPartitions().contains(routePartition)) {
                continue;
            }
            if (lease.ownerInstanceId() == null || lease.ownerInstanceId().isBlank()) {
                log.warn("UI_GATEWAY_SHARD_GROUP_UNOWNED. eqpId={}, routePartition={}, groupId={}",
                        manifest.eqpId(), routePartition, lease.groupId());
                return Optional.empty();
            }
            return Optional.of(lease.ownerInstanceId());
        }

        log.warn("UI_GATEWAY_SHARD_GROUP_NOT_FOUND. eqpId={}, routePartition={}",
                manifest.eqpId(), routePartition);
        return Optional.empty();
    }

    /**
     * manifest 가 라우팅할 partition 을 정합니다 — descriptor 값이 우선이고, 없으면 DB 값입니다.
     *
     * @param manifest 원장에 고정된 manifest
     * @return route partition. 어느 출처에도 없으면 empty
     */
    private Optional<Integer> routePartitionOf(final UiOperationManifestView manifest) {
        final String primaryResourceKey =
                manifest.resourceKeys().isEmpty() ? "" : manifest.resourceKeys().get(0);
        final String descriptorJson = manifest.descriptorJsonByResourceKey().get(primaryResourceKey);
        final Integer fromDescriptor = descriptorJson == null
                ? null
                : descriptorReader.read(descriptorJson).routePartition();
        if (fromDescriptor != null) {
            return Optional.of(fromDescriptor);
        }
        return routePartitionLookupPort.findRoutePartition(manifest.eqpId());
    }
}
