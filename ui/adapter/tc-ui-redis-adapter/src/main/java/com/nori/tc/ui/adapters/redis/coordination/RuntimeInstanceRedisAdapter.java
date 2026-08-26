package com.nori.tc.ui.adapters.redis.coordination;

import com.nori.tc.common.coordination.store.MembershipRedisStore;
import com.nori.tc.ui.adapters.redis.config.UiRuntimeInstanceProperties;
import com.nori.tc.ui.core.port.redis.RuntimeInstanceQueryPort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.nori.tc.ui.adapters.redis.coordination.UiCoordinationFailures.translating;

/**
 * coordination membership 원장 위의 런타임 인스턴스 조망 어댑터입니다(§9.1).
 *
 * <p>members index 는 만료 session 도 담는 정리형이므로(재등록 차단 근거) index 를 훑고 member key 가
 * 살아 있는 것만 남깁니다 — 살아 있는 것의 판정은 heartbeat TTL 이 유일한 근거입니다.</p>
 *
 * <p>membership 상태({@code JOINING}/{@code FENCED} 등)로 걸러내지 않습니다 — 이 포트는 "어느 Pod 이
 * 살아 있나"만 답하고, 그 Pod 이 슬롯을 이어받을 수 있는지는 {@code state} 를 받은 소비자가 판정합니다.
 * 여기서 걸러 버리면 heartbeat 는 살아 있는데 화면에서 사라지는 Pod 이 생깁니다.</p>
 */
public final class RuntimeInstanceRedisAdapter implements RuntimeInstanceQueryPort {

    private final MembershipRedisStore membershipStore;
    private final String gatewayServiceName;
    private final String businessServiceName;
    private final UiRuntimeInstanceProperties properties;

    /**
     * 어댑터를 만듭니다.
     *
     * @param membershipStore 공통 membership store
     * @param gatewayServiceName Gateway 의 coordination serviceName(= membership key namespace)
     * @param businessServiceName Business 의 coordination serviceName(= membership key namespace)
     * @param properties 조회 상한 설정
     */
    public RuntimeInstanceRedisAdapter(
            final MembershipRedisStore membershipStore,
            final String gatewayServiceName,
            final String businessServiceName,
            final UiRuntimeInstanceProperties properties
    ) {
        this.membershipStore = Objects.requireNonNull(membershipStore, "membershipStore is null");
        this.gatewayServiceName = Objects.requireNonNull(gatewayServiceName, "gatewayServiceName is null");
        this.businessServiceName = Objects.requireNonNull(businessServiceName, "businessServiceName is null");
        this.properties = Objects.requireNonNull(properties, "properties is null");
    }

    @Override
    public List<RuntimeInstanceView> findGatewayInstances() {
        return findInstances(gatewayServiceName);
    }

    @Override
    public List<RuntimeInstanceView> findBusinessInstances() {
        return findInstances(businessServiceName);
    }

    /**
     * 해당 serviceName namespace 의 살아 있는 인스턴스를 조회합니다.
     *
     * @param serviceName membership key namespace
     * @return sessionId 오름차순 인스턴스 목록
     */
    private List<RuntimeInstanceView> findInstances(final String serviceName) {
        final long limit = properties.getScanLimit();

        return translating(() -> {
            final List<RuntimeInstanceView> instances = new ArrayList<>();
            for (final String sessionId : membershipStore.knownSessionIds(serviceName, limit)) {
                membershipStore.readMember(serviceName, sessionId)
                        .map(fields -> toView(sessionId, fields))
                        .ifPresent(instances::add);
            }
            instances.sort(Comparator.comparing(RuntimeInstanceView::sessionId));
            return List.copyOf(instances);
        });
    }

    /**
     * member hash 를 조망 뷰로 옮깁니다.
     *
     * @param sessionId session 식별자
     * @param fields member hash field map
     * @return 조망 뷰
     */
    private static RuntimeInstanceView toView(final String sessionId, final Map<String, String> fields) {
        return new RuntimeInstanceView(
                sessionId,
                blankToNull(fields.get("podName")),
                blankToNull(fields.get("podIp")),
                blankToNull(fields.get("state")),
                blankToNull(fields.get("protocolVersion")),
                parseLongOrNull(fields.get("registeredAt")),
                parseLongOrNull(fields.get("updatedAt"))
        );
    }

    private static String blankToNull(final String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Long parseLongOrNull(final String value) {
        return Optional.ofNullable(blankToNull(value))
                .map(text -> {
                    try {
                        return Long.valueOf(text);
                    } catch (final NumberFormatException notANumber) {
                        return null;
                    }
                })
                .orElse(null);
    }
}
