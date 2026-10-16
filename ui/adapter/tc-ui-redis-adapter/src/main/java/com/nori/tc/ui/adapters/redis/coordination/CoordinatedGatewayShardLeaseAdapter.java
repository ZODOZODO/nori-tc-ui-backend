package com.nori.tc.ui.adapters.redis.coordination;

import com.nori.tc.common.coordination.CoordinationKeys;
import com.nori.tc.common.coordination.store.FencedLeaseRedisStore;
import com.nori.common.shard.SharedShardGroup;
import com.nori.tc.common.shard.SharedShardGroupProperties;
import com.nori.tc.ui.core.port.redis.GatewayShardLeasePort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.nori.tc.ui.adapters.redis.coordination.UiCoordinationFailures.translating;

/**
 * Gateway shard lease 점유 현황을 <b>두 기록 스키마 모두</b>에서 읽는 어댑터입니다(§12.2).
 *
 * <p>같은 사실(어느 group 을 누가 점유했나)이 Gateway 버전에 따라 다른 키에 기록됩니다.</p>
 * <ul>
 *   <li>조정을 쓰는 Gateway: {@code tc:coord:v1:gateway:shard:<groupId>:lease} hash(fence epoch 포함)</li>
 *   <li>그 이전 Gateway: {@code <key-prefix>:<groupId>} raw String</li>
 * </ul>
 *
 * <p>읽는 쪽이 한 스키마만 보면 <b>ui-backend 와 Gateway 의 배포 순서</b>가 화면의 정확성을 좌우합니다 —
 * 라이브 실측(2026-08-26)에서 Redis 에는 {@code tc:coord:v1:*} 가 0 건이고
 * {@code tc:gateway:lease:group-1/2} 만 존재했습니다. 조정 키만 읽으면 그 상태에서 전 group 이
 * 미점유로 보이고, 설비 목록은 shard-group 설정 기반이라 그대로 남아 오표시를 눈치채기 어렵습니다.
 * 그래서 조정 키를 먼저 보고, 소유자가 없는 group 만 레거시 키로 확인합니다.</p>
 *
 * <p>{@code ownerInstanceId} 는 <b>어느 쪽이 답했는지에 따라 형식이 다른 불투명한 소유 토큰</b>입니다 —
 * 조정 쪽은 {@code serviceName:podUid:bootId}(membership 원장의 member key 와 같은 문자열이라 Pod 이름
 * 조인이 됩니다), 레거시 쪽은 실측값이 {@code tc-gateway-5bdc8cdc4d-g8zs5-c7e1699b-...} 로 session 식별자가
 * 아닙니다. 그러므로 이 값은 <b>점유 여부 판정</b>에만 쓰고, Pod 이름은 조인이 성립할 때만 붙습니다.</p>
 */
public final class CoordinatedGatewayShardLeaseAdapter implements GatewayShardLeasePort {

    private final FencedLeaseRedisStore leaseStore;
    private final SharedShardGroupProperties shardGroupProperties;
    private final GatewayShardLeasePort legacyLeaseSource;

    /**
     * 어댑터를 만듭니다.
     *
     * @param leaseStore 공통 fenced lease store
     * @param shardGroupProperties Gateway 와 공유하는 shard-group 정의
     * @param legacyLeaseSource 조정 이전 스키마(raw String 키) 조회원
     */
    public CoordinatedGatewayShardLeaseAdapter(
            final FencedLeaseRedisStore leaseStore,
            final SharedShardGroupProperties shardGroupProperties,
            final GatewayShardLeasePort legacyLeaseSource
    ) {
        this.leaseStore = Objects.requireNonNull(leaseStore, "leaseStore is null");
        this.shardGroupProperties = Objects.requireNonNull(shardGroupProperties, "shardGroupProperties is null");
        this.legacyLeaseSource = Objects.requireNonNull(legacyLeaseSource, "legacyLeaseSource is null");
    }

    @Override
    public List<GatewayShardLeaseView> findAllLeases() {
        final List<SharedShardGroup> groups = shardGroupProperties.getShardGroups();
        final Map<String, String> coordinatedOwners = new LinkedHashMap<>();
        boolean anyUnowned = false;
        for (final SharedShardGroup group : groups) {
            final String owner = ownerSessionIdOf(group.getId());
            if (owner == null) {
                anyUnowned = true;
            } else {
                coordinatedOwners.put(group.getId(), owner);
            }
        }

        // 레거시 조회는 group 전체를 한 번에 읽으므로, 조정 키로 다 채워졌으면 아예 부르지 않는다.
        final Map<String, String> legacyOwners = anyUnowned ? legacyOwners() : Map.of();

        final List<GatewayShardLeaseView> leases = new ArrayList<>();
        for (final SharedShardGroup group : groups) {
            final String coordinatedOwner = coordinatedOwners.get(group.getId());
            leases.add(new GatewayShardLeaseView(
                    group.getId(),
                    List.copyOf(group.getOwnedPartitions()),
                    coordinatedOwner != null ? coordinatedOwner : legacyOwners.get(group.getId())
            ));
        }
        return List.copyOf(leases);
    }

    /**
     * 해당 shard group 의 현재 소유 session 을 조정 lease 에서 반환합니다.
     *
     * <p>epoch 0 이나 빈 sessionId 는 소유로 보지 않습니다 — Gateway 측 {@code currentOwner} 판정과
     * 같은 규칙이어야 두 쪽이 같은 답을 냅니다.</p>
     *
     * @param groupId shard group id
     * @return 소유 sessionId. 미점유면 null
     */
    private String ownerSessionIdOf(final String groupId) {
        return translating(() -> leaseStore.currentLease(CoordinationKeys.gatewayShardLeaseKey(groupId)))
                .filter(snapshot -> snapshot.epoch() > 0L && !snapshot.sessionId().isBlank())
                .map(FencedLeaseRedisStore.LeaseSnapshot::sessionId)
                .orElse(null);
    }

    /**
     * 레거시 raw String 키의 점유 현황을 groupId 별로 모읍니다.
     *
     * @return groupId → 소유 토큰(점유된 group 만)
     */
    private Map<String, String> legacyOwners() {
        final Map<String, String> owners = new HashMap<>();
        for (final GatewayShardLeaseView view : legacyLeaseSource.findAllLeases()) {
            if (view.ownerInstanceId() != null && !view.ownerInstanceId().isBlank()) {
                owners.put(view.groupId(), view.ownerInstanceId());
            }
        }
        return owners;
    }
}
