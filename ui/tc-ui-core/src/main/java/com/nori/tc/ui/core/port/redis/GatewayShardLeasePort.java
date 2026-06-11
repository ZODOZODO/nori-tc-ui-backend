package com.nori.tc.ui.core.port.redis;

import java.util.List;

/**
 * Gateway shard lease 점유 현황 조회 포트입니다.
 *
 * <p>Gateway 다중 인스턴스 환경에서 각 인스턴스는 shard-group 슬롯 하나를 Redis lease로 잡아
 * owned partition을 결정합니다. 본 포트는 설정된 shard-group 목록과 각 group의 현재 lease 소유
 * 인스턴스를 조회합니다. UI는 이 결과를 기준으로 설비를 gateway 단위 그룹으로 분류합니다.</p>
 */
public interface GatewayShardLeasePort {

    /**
     * 설정된 모든 shard-group의 lease 점유 현황을 조회합니다.
     *
     * @return shard-group 정의 순서를 보존한 lease 현황 목록.
     *         shard-group 매핑이 비어 있으면(단일 인스턴스 호환 모드) 빈 목록.
     */
    List<GatewayShardLeaseView> findAllLeases();

    /**
     * 단일 shard-group의 lease 점유 현황입니다.
     *
     * @param groupId shard group id (예: group-1)
     * @param ownedPartitions 본 group에 속한 route partition 목록
     * @param ownerInstanceId 현재 lease를 보유한 gateway 인스턴스 식별자. 미점유면 null.
     */
    record GatewayShardLeaseView(
            String groupId,
            List<Integer> ownedPartitions,
            String ownerInstanceId
    ) {
    }
}
