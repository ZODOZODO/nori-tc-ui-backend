package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiMembershipState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §9.1 session identity 와 §9.3 lease 안전 관계식을 고정합니다(Plan §8 B22/B25/B26).
 */
class UiCoordinationSessionAndPropertiesTest {

    @Test
    @DisplayName("§9.1 — sessionId 는 serviceName:podUid:bootId 다")
    void sessionIdFormat() {
        final UiCoordinationSession session =
                new UiCoordinationSession("tc-ui-backend", "uid-1", "boot-1", "pod-1");

        assertEquals("tc-ui-backend:uid-1:boot-1", session.sessionId());
        assertEquals("pod-1", session.podName(), "podName 은 UID 가 아니라 관측용 Pod 이름이어야 합니다.");
    }

    @Test
    @DisplayName("B25 — Kubernetes 런타임에서 POD_UID 가 없으면 프로퍼티와 무관하게 기동 실패다")
    void kubernetesRuntimeRequiresPodUidRegardlessOfProperty() {
        final IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> UiCoordinationSession.create("tc-ui-backend", null, "host", true, true));

        assertTrue(failure.getMessage().contains("Downward API"));
    }

    @Test
    @DisplayName("로컬 개발 구성만 hostname 대체를 허용한다")
    void localProfileMayFallBackToHostname() {
        assertThrows(IllegalStateException.class,
                () -> UiCoordinationSession.create("tc-ui-backend", null, "host", false, false));

        final UiCoordinationSession local =
                UiCoordinationSession.create("tc-ui-backend", null, "host", true, false);
        assertEquals("tc-ui-backend:host:" + local.bootId(), local.sessionId());
    }

    @Test
    @DisplayName("B22 — FENCED 는 프로세스 안에서도 종착 상태다")
    void fencedIsTerminalInsideTheProcess() {
        final UiCoordinationSession session =
                new UiCoordinationSession("tc-ui-backend", "uid-1", "boot-1", "pod-1");
        session.transitionTo(UiMembershipState.ACTIVE_SYNCED);
        session.fence();

        assertFalse(session.transitionTo(UiMembershipState.ACTIVE_SYNCED),
                "fence 이후 되살아나면 소유권 없는 session 이 확정을 수행합니다.");
        assertEquals(UiMembershipState.FENCED, session.state());
        assertFalse(session.active());
    }

    @Test
    @DisplayName("관측 revision 은 단조 증가로만 기록된다")
    void observedRevisionIsMonotonic() {
        final UiCoordinationSession session =
                new UiCoordinationSession("tc-ui-backend", "uid-1", "boot-1", "pod-1");

        session.observeGlobalRevision(7L);
        session.observeGlobalRevision(3L);

        assertEquals(7L, session.lastObservedGlobalRevision());
    }

    @Test
    @DisplayName("§9.3 — 안전 관계식을 못 지키는 설정은 기동을 실패시킨다")
    void leaseSafetyViolationFailsStartup() {
        final UiCoordinationProperties properties = new UiCoordinationProperties();
        properties.setHeartbeatIntervalMs(14_000L);
        properties.setHeartbeatTtlMs(15_000L);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    @DisplayName("B26 — 관계식은 선언값이 아니라 실측 Redis timeout 으로 다시 강제된다")
    void actualRedisTimeoutIsEnforcedOverDeclaredValue() {
        final UiCoordinationProperties properties = new UiCoordinationProperties();
        properties.validate();

        /*
         * 선언값(1s)으로는 통과하지만 실제 client timeout 이 8s 면
         * worstCase(8s + selfFence 2s + skew 1s = 11s) 가 예산(ttl 15s − heartbeat 5s = 10s)을 넘는다.
         */
        assertThrows(IllegalStateException.class,
                () -> properties.assertLeaseSafetyWithActualRedisTimeout(8_000L));
        properties.assertLeaseSafetyWithActualRedisTimeout(5_000L);
    }

    @Test
    @DisplayName("조정 비활성 구성은 관계식 검증을 건너뛴다")
    void disabledCoordinationSkipsValidation() {
        final UiCoordinationProperties properties = new UiCoordinationProperties();
        properties.setEnabled(false);
        properties.setHeartbeatTtlMs(1L);

        properties.validate();
        properties.assertLeaseSafetyWithActualRedisTimeout(60_000L);
    }
}
