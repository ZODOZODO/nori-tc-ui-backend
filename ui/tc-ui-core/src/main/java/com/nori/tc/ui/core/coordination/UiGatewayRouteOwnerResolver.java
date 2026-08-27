package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
import com.nori.tc.ui.core.coordination.port.UiGatewayParticipantResolver;
import com.nori.tc.ui.core.port.messaging.UiGatewayEqpRoutePartitionLookupPort;
import com.nori.tc.ui.core.port.redis.GatewayShardLeasePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * route partition 의 shard lease 소유자를 Gateway 참여자로 정합니다(§11.3/§12.2).
 *
 * <p>partition 결정은 {@link UiGatewayRoutePlan} 한 곳에서 합니다 — 명령 발행 경로와 <b>같은 계산</b>을
 * 써야 하기 때문입니다. 참여자 판정이 다른 출처를 쓰면 "명령은 A Pod 에, 증적 요구는 B Pod 에" 가 되어
 * barrier 가 닫히지 않습니다.</p>
 *
 * <p>이동(§12.5)은 옛 owner 와 새 owner 를 <b>모두</b> 참여자로 냅니다. 옛 owner 는 PREPARE 에서
 * release 하고 PREPARED proof 를 남기며, 전원의 prepare proof 로 COMMITTED 에 도달한 사실이 곧
 * release 증명이라서 새 owner 는 그 뒤에야 bind 합니다. 새 owner 만 고정하면 아무도 release 하지 못하는
 * 이동을 기다리다 EQP_ROUTE_MOVE_NOT_RELEASED 로 abort 됩니다(운영 실측).</p>
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
     * @param routePartitionLookupPort eqpId -&gt; route_partition 조회 포트
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
    public List<String> ownerSessionIds(final UiOperationManifestView manifest) {
        Objects.requireNonNull(manifest, "manifest is null");
        final UiGatewayRoutePlan plan =
                UiGatewayRoutePlan.of(manifest, descriptorReader, routePartitionLookupPort);
        if (plan.target() == null) {
            log.warn("UI_GATEWAY_ROUTE_PARTITION_UNRESOLVED. eqpId={}, eventType={}",
                    manifest.eqpId(), manifest.eventType());
            return List.of();
        }

        /*
         * 이동은 옛 owner 를 먼저 담는다 — 집합이라 순서가 프로토콜에 영향을 주지는 않지만, 운영 로그에서
         * "release 대상 -> bind 대상" 순으로 읽히는 편이 진단에 낫다.
         */
        final List<Integer> partitions = new ArrayList<>();
        if (plan.isMove()) {
            partitions.add(plan.previous());
        }
        partitions.add(plan.target());

        final Set<String> owners = new LinkedHashSet<>();
        for (final Integer partition : partitions) {
            final Optional<String> owner = ownerOf(manifest, partition);
            if (owner.isEmpty()) {
                return List.of();
            }
            owners.add(owner.get());
        }
        if (plan.isMove()) {
            log.info("UI_GATEWAY_ROUTE_MOVE_PARTICIPANTS. eqpId={}, previousPartition={}, targetPartition={},"
                            + " owners={}",
                    manifest.eqpId(), plan.previous(), plan.target(), owners);
        }
        return List.copyOf(owners);
    }

    private Optional<String> ownerOf(final UiOperationManifestView manifest, final int routePartition) {
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
}
