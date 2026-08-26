package com.nori.tc.ui.starter;

import com.nori.tc.ui.core.coordination.UiCoordinationStatus;
import com.nori.tc.ui.core.coordination.model.UiMembershipState;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.Objects;

/**
 * 조정 session 이 영구 fence 되었는지만 보는 liveness contributor 입니다(§9.1 / §12.7).
 *
 * <p><b>왜 {@code uiBackendRuntime} 을 liveness 에 넣지 않는가</b> — 그 지표는 Redis ping 실패에도
 * DOWN 이 됩니다. 외부 의존 장애로 DOWN 이 된 Pod 을 재시작해도 장애는 낫지 않고, 전 Pod 이 동시에
 * 재시작할 뿐입니다. 반대로 {@code FENCED} 는 이 JVM 안에서 되돌릴 수 없는 종착 상태라
 * ({@code UiCoordinationSession.transitionTo} 가 나가는 전이를 거부) 새 bootId 로 다시 기동하는 것이
 * <b>유일한</b> 회복 경로입니다. 그래서 liveness 판정에는 이 한 가지만 넣습니다.</p>
 *
 * <p>이것이 없으면 fence 된 Pod 은 readiness 503 으로 트래픽에서만 빠지고 liveness 는 계속 통과해,
 * Redis 가 회복돼도 아무도 프로세스를 교체하지 않습니다 — 영구 무응답 Pod 이 됩니다(§23.9).</p>
 */
public class UiCoordinationFenceHealthIndicator implements HealthIndicator {

    private final UiCoordinationStatus coordinationStatus;

    /**
     * fence 판정 contributor 를 만듭니다.
     *
     * @param coordinationStatus 조정 상태 표면(조정 비활성 구성이면 항상 {@code ACTIVE_SYNCED} 를 보고)
     */
    public UiCoordinationFenceHealthIndicator(final UiCoordinationStatus coordinationStatus) {
        this.coordinationStatus = Objects.requireNonNull(coordinationStatus, "coordinationStatus is null");
    }

    @Override
    public Health health() {
        final UiMembershipState state = coordinationStatus.membershipState();
        final Health.Builder builder = state == UiMembershipState.FENCED ? Health.down() : Health.up();
        return builder
                .withDetail("membershipState", state.name())
                .withDetail("coordinationEnabled", coordinationStatus.coordinationEnabled())
                .build();
    }
}
