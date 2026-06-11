package com.nori.tc.ui.adapters.redis.lease;

import com.nori.tc.common.shard.SharedShardGroup;
import com.nori.tc.common.shard.SharedShardGroupProperties;
import com.nori.tc.ui.adapters.redis.config.UiGatewayLeaseProperties;
import com.nori.tc.ui.core.port.redis.GatewayShardLeasePort.GatewayShardLeaseView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GatewayShardLeaseRedisAdapterTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private SharedShardGroupProperties shardGroupProperties;
    private UiGatewayLeaseProperties leaseProperties;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        shardGroupProperties = new SharedShardGroupProperties();
        leaseProperties = new UiGatewayLeaseProperties();
        leaseProperties.setKeyPrefix("tc:gateway:lease");
    }

    private GatewayShardLeaseRedisAdapter adapter() {
        return new GatewayShardLeaseRedisAdapter(stringRedisTemplate, shardGroupProperties, leaseProperties);
    }

    private static SharedShardGroup group(final String id, final List<Integer> partitions) {
        final SharedShardGroup group = new SharedShardGroup();
        group.setId(id);
        group.setOwnedPartitions(partitions);
        return group;
    }

    @Test
    void returnsLeaseOwnerPerGroupPreservingDefinitionOrder() {
        shardGroupProperties.setShardGroups(List.of(
                group("group-1", List.of(0, 1)),
                group("group-2", List.of(2, 3))
        ));
        when(valueOperations.get("tc:gateway:lease:group-1")).thenReturn("pod-a-uuid-1");
        when(valueOperations.get("tc:gateway:lease:group-2")).thenReturn(null);

        final List<GatewayShardLeaseView> leases = adapter().findAllLeases();

        assertEquals(2, leases.size());
        assertEquals("group-1", leases.get(0).groupId());
        assertEquals(List.of(0, 1), leases.get(0).ownedPartitions());
        assertEquals("pod-a-uuid-1", leases.get(0).ownerInstanceId());
        assertEquals("group-2", leases.get(1).groupId());
        assertEquals(List.of(2, 3), leases.get(1).ownedPartitions());
        assertNull(leases.get(1).ownerInstanceId());
    }

    @Test
    void emptyShardGroupConfigReturnsEmptyList() {
        shardGroupProperties.setShardGroups(List.of());

        assertTrue(adapter().findAllLeases().isEmpty());
    }

    @Test
    void redisFailurePropagatesException() {
        shardGroupProperties.setShardGroups(List.of(group("group-1", List.of(0, 1))));
        when(valueOperations.get("tc:gateway:lease:group-1"))
                .thenThrow(new IllegalStateException("redis down"));

        assertThrows(IllegalStateException.class, () -> adapter().findAllLeases());
    }
}
