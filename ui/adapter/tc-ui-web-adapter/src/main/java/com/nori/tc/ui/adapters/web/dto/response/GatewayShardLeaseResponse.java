package com.nori.tc.ui.adapters.web.dto.response;

import java.util.List;

/**
 * Gateway shard lease 점유 현황 응답 DTO입니다.
 *
 * @param groupId shard group id (예: group-1)
 * @param ownedPartitions 본 group에 속한 route partition 목록
 * @param ownerInstanceId 현재 lease를 보유한 gateway 인스턴스 식별자. 미점유면 null.
 */
public record GatewayShardLeaseResponse(
        String groupId,
        List<Integer> ownedPartitions,
        String ownerInstanceId
) {
}
