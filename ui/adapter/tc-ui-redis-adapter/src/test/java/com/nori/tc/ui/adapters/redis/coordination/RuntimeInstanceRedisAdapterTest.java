package com.nori.tc.ui.adapters.redis.coordination;

import com.nori.tc.common.coordination.store.MembershipRedisStore;
import com.nori.tc.ui.adapters.redis.config.UiRuntimeInstanceProperties;
import com.nori.tc.ui.core.port.redis.RuntimeInstanceQueryPort.RuntimeInstanceView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeInstanceRedisAdapterTest {

    private static final String GATEWAY_SERVICE_NAME = "tc-gateway";
    private static final String BUSINESS_SERVICE_NAME = "tc-business";

    @Mock
    private MembershipRedisStore membershipStore;

    private UiRuntimeInstanceProperties properties;

    @BeforeEach
    void setUp() {
        properties = new UiRuntimeInstanceProperties();
    }

    private RuntimeInstanceRedisAdapter adapter() {
        return new RuntimeInstanceRedisAdapter(
                membershipStore, GATEWAY_SERVICE_NAME, BUSINESS_SERVICE_NAME, properties);
    }

    private static Map<String, String> member(final String podName, final String podIp, final String state) {
        return Map.of(
                "podName", podName,
                "podIp", podIp,
                "state", state,
                "protocolVersion", "1",
                "registeredAt", "1000",
                "updatedAt", "2000"
        );
    }

    @Test
    @DisplayName("index 의 session 중 member key 가 살아 있는 것만 인스턴스로 돌려준다")
    void keepsOnlyLiveSessions() {
        when(membershipStore.knownSessionIds(GATEWAY_SERVICE_NAME, properties.getScanLimit()))
                .thenReturn(List.of("tc-gateway:uid-b:boot-b", "tc-gateway:uid-a:boot-a", "tc-gateway:uid-x:boot-x"));
        when(membershipStore.readMember(GATEWAY_SERVICE_NAME, "tc-gateway:uid-b:boot-b"))
                .thenReturn(Optional.of(member("gw-pod-b", "10.244.3.167", "ACTIVE_SYNCED")));
        when(membershipStore.readMember(GATEWAY_SERVICE_NAME, "tc-gateway:uid-a:boot-a"))
                .thenReturn(Optional.of(member("gw-pod-a", "10.244.1.52", "JOINING")));
        // TTL 이 만료된 session — index 에는 남아 있으나 살아 있지 않다.
        when(membershipStore.readMember(GATEWAY_SERVICE_NAME, "tc-gateway:uid-x:boot-x"))
                .thenReturn(Optional.empty());

        final List<RuntimeInstanceView> instances = adapter().findGatewayInstances();

        assertEquals(2, instances.size());
        assertEquals("tc-gateway:uid-a:boot-a", instances.get(0).sessionId(), "sessionId 오름차순");
        assertEquals("gw-pod-a", instances.get(0).podName());
        assertEquals("10.244.1.52", instances.get(0).podIp());
        assertEquals("JOINING", instances.get(0).state());
        assertEquals("tc-gateway:uid-b:boot-b", instances.get(1).sessionId());
        assertEquals(1000L, instances.get(1).registeredAtEpochMs());
        assertEquals(2000L, instances.get(1).updatedAtEpochMs());
    }

    @Test
    @DisplayName("business 축은 business serviceName namespace 를 조회한다")
    void queriesBusinessNamespaceForBusinessAxis() {
        // 두 축의 조인 키가 달라 namespace 도 다르다 — gateway 는 sessionId, business 는 Pod IP 로 맞춘다.
        when(membershipStore.knownSessionIds(BUSINESS_SERVICE_NAME, properties.getScanLimit()))
                .thenReturn(List.of("tc-business:uid-a:boot-a"));
        when(membershipStore.readMember(BUSINESS_SERVICE_NAME, "tc-business:uid-a:boot-a"))
                .thenReturn(Optional.of(member("tc-business-5b7fd78965-987d9", "10.244.2.80", "ACTIVE_SYNCED")));

        final List<RuntimeInstanceView> instances = adapter().findBusinessInstances();

        assertEquals(1, instances.size());
        assertEquals("tc-business-5b7fd78965-987d9", instances.get(0).podName());
        assertEquals("10.244.2.80", instances.get(0).podIp());
    }

    @Test
    @DisplayName("membership 상태로 걸러내지 않는다 — 판정 근거는 소비자에게 넘긴다")
    void doesNotFilterByMembershipState() {
        /*
         * FENCED session 도 heartbeat 를 계속 갱신하므로 member key 는 살아 있다. 여기서 지워 버리면
         * "떠 있는 Pod 수" 자체가 틀리고, 남겨서 대기로 단정하면 거짓이 된다 — 상태를 실어 보낸다.
         */
        when(membershipStore.knownSessionIds(GATEWAY_SERVICE_NAME, properties.getScanLimit()))
                .thenReturn(List.of("tc-gateway:uid-f:boot-f"));
        when(membershipStore.readMember(GATEWAY_SERVICE_NAME, "tc-gateway:uid-f:boot-f"))
                .thenReturn(Optional.of(member("gw-pod-f", "10.244.2.142", "FENCED")));

        final List<RuntimeInstanceView> instances = adapter().findGatewayInstances();

        assertEquals(1, instances.size());
        assertEquals("FENCED", instances.get(0).state());
    }

    @Test
    @DisplayName("숫자가 아닌 시각 필드와 빈 필드는 null 로 내려간다")
    void mapsUnparsableAndBlankFieldsToNull() {
        when(membershipStore.knownSessionIds(GATEWAY_SERVICE_NAME, properties.getScanLimit()))
                .thenReturn(List.of("tc-gateway:uid-a:boot-a"));
        when(membershipStore.readMember(GATEWAY_SERVICE_NAME, "tc-gateway:uid-a:boot-a"))
                .thenReturn(Optional.of(Map.of(
                        "podName", "  ", "podIp", "", "state", "ACTIVE_SYNCED", "updatedAt", "not-a-number")));

        final RuntimeInstanceView instance = adapter().findGatewayInstances().get(0);

        assertNull(instance.podName());
        assertNull(instance.podIp(), "podIp 미기록 session(구 Pod)은 조인 키가 없다");
        assertNull(instance.protocolVersion());
        assertNull(instance.registeredAtEpochMs());
        assertNull(instance.updatedAtEpochMs());
    }

    @Test
    @DisplayName("index 가 비면 빈 목록이다")
    void returnsEmptyWhenIndexIsEmpty() {
        when(membershipStore.knownSessionIds(GATEWAY_SERVICE_NAME, properties.getScanLimit()))
                .thenReturn(List.of());

        assertTrue(adapter().findGatewayInstances().isEmpty());
    }
}
