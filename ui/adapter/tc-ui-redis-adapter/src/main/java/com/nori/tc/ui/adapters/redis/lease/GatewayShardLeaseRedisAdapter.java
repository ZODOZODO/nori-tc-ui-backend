package com.nori.tc.ui.adapters.redis.lease;

import com.nori.tc.common.shard.SharedShardGroup;
import com.nori.tc.common.shard.SharedShardGroupProperties;
import com.nori.tc.ui.adapters.redis.config.UiGatewayLeaseProperties;
import com.nori.tc.ui.core.port.redis.GatewayShardLeasePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Gateway shard lease 점유 현황을 Redis에서 조회하는 어댑터입니다.
 *
 * <p>shard-group 정의는 Gateway와 공유하는 {@code tc.shard-groups[*]} 설정
 * ({@link SharedShardGroupProperties})에서 읽고, 각 group의 lease 소유 인스턴스는
 * Gateway가 기록한 {@code <tc.gateway.lease.key-prefix>:<groupId>} 키에서 읽습니다.</p>
 *
 * <p>Gateway는 lease 값을 raw String(StringRedisSerializer)으로 기록하므로
 * JSON 직렬화 템플릿이 아닌 {@code businessStringRedisTemplate}으로 읽습니다.</p>
 */
@Service
@EnableConfigurationProperties(UiGatewayLeaseProperties.class)
public class GatewayShardLeaseRedisAdapter implements GatewayShardLeasePort {

    private static final Logger log = LoggerFactory.getLogger(GatewayShardLeaseRedisAdapter.class);

    private final StringRedisTemplate businessStringRedisTemplate;
    private final SharedShardGroupProperties shardGroupProperties;
    private final UiGatewayLeaseProperties leaseProperties;

    public GatewayShardLeaseRedisAdapter(
            @Qualifier("businessStringRedisTemplate") final StringRedisTemplate businessStringRedisTemplate,
            final SharedShardGroupProperties shardGroupProperties,
            final UiGatewayLeaseProperties leaseProperties
    ) {
        this.businessStringRedisTemplate = Objects.requireNonNull(businessStringRedisTemplate,
                "businessStringRedisTemplate is null");
        this.shardGroupProperties = Objects.requireNonNull(shardGroupProperties,
                "shardGroupProperties is null");
        this.leaseProperties = Objects.requireNonNull(leaseProperties, "leaseProperties is null");
    }

    @Override
    public List<GatewayShardLeaseView> findAllLeases() {
        final List<GatewayShardLeaseView> leases = new ArrayList<>();
        for (SharedShardGroup group : shardGroupProperties.getShardGroups()) {
            final String key = leaseProperties.getKeyPrefix() + ":" + group.getId();
            final String ownerInstanceId;
            try {
                ownerInstanceId = businessStringRedisTemplate.opsForValue().get(key);
            } catch (Exception redisException) {
                log.error("shard lease 조회 실패. groupId={}, key={}", group.getId(), key, redisException);
                throw redisException;
            }
            leases.add(new GatewayShardLeaseView(
                    group.getId(),
                    List.copyOf(group.getOwnedPartitions()),
                    ownerInstanceId
            ));
        }
        return leases;
    }
}
