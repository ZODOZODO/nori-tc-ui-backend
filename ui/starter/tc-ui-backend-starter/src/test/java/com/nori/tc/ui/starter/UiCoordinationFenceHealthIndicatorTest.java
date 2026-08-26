package com.nori.tc.ui.starter;

import com.nori.tc.ui.core.coordination.UiCoordinationStatus;
import com.nori.tc.ui.core.coordination.model.UiMembershipState;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * §9.1 / §12.7 / §23.9 — liveness 는 <b>영구 fence 만</b> 보고 DOWN 이 되어야 합니다.
 *
 * <p>fence 는 이 JVM 안에서 되돌릴 수 없으므로 프로세스 교체가 유일한 회복 경로입니다. 반대로
 * 외부 의존(Redis) 장애나 sync 미완료로 DOWN 을 주면 재시작으로 낫지 않는 상황에서 전 Pod 이
 * 동시에 재시작합니다.</p>
 */
class UiCoordinationFenceHealthIndicatorTest {

    @Test
    void fencedSessionIsDownSoLivenessReplacesTheProcess() {
        final UiCoordinationFenceHealthIndicator indicator =
                new UiCoordinationFenceHealthIndicator(statusOf(UiMembershipState.FENCED));

        assertEquals(Status.DOWN, indicator.health().getStatus());
        assertEquals("FENCED", indicator.health().getDetails().get("membershipState"),
                "운영자가 재시작 사유를 알 수 있어야 합니다.");
    }

    @Test
    void joiningSessionStaysUpSoStartupIsNotKilled() {
        final UiCoordinationFenceHealthIndicator indicator =
                new UiCoordinationFenceHealthIndicator(statusOf(UiMembershipState.JOINING));

        // sync 미완료는 기다리면 풀린다 — readiness 가 막을 일이지 liveness 가 죽일 일이 아니다.
        assertEquals(Status.UP, indicator.health().getStatus());
    }

    @Test
    void coordinationDisabledStaysUp() {
        final UiCoordinationFenceHealthIndicator indicator =
                new UiCoordinationFenceHealthIndicator(UiCoordinationStatus.disabled());

        assertEquals(Status.UP, indicator.health().getStatus());
    }

    private static UiCoordinationStatus statusOf(final UiMembershipState state) {
        return new UiCoordinationStatus() {
            @Override
            public boolean coordinationEnabled() {
                return true;
            }

            @Override
            public UiMembershipState membershipState() {
                return state;
            }

            @Override
            public boolean readyForMutation() {
                return state == UiMembershipState.ACTIVE_SYNCED;
            }

            @Override
            public Map<String, Object> details() {
                return Map.of("membershipState", state.name());
            }
        };
    }
}
