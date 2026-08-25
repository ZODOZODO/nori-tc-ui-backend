package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiMembershipState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 한 번의 JVM 생명주기를 식별하는 UI Backend session 입니다(§9.1/§9.2).
 *
 * <p>운영 sessionId 는 {@code serviceName:POD_UID:bootId} 입니다. {@code POD_UID} 는 Downward API 로
 * 주입되어야 하며, Kubernetes 런타임에서 누락되면 프로퍼티와 무관하게 기동을 실패시킵니다 — 프로퍼티만
 * 게이트로 두면 운영에서 값을 켜는 순간 zombie 판별이 불가능한 hostname 식별자가 조용히 허용됩니다
 * (Plan §8 B25).</p>
 */
public final class UiCoordinationSession {

    private static final Logger log = LoggerFactory.getLogger(UiCoordinationSession.class);

    private final String serviceName;
    private final String podUid;
    private final String podName;
    private final String bootId;
    private final String sessionId;
    private final AtomicReference<UiMembershipState> state = new AtomicReference<>(UiMembershipState.JOINING);
    private final AtomicLong lastObservedGlobalRevision = new AtomicLong(0L);

    /**
     * session identity 를 고정합니다.
     *
     * @param serviceName 서비스명
     * @param podUid Pod UID
     * @param bootId JVM 시작 시 생성한 UUID
     * @param podName Pod 이름(관측용 — UID 와 별개)
     */
    public UiCoordinationSession(
            final String serviceName,
            final String podUid,
            final String bootId,
            final String podName
    ) {
        this.serviceName = requirePart("serviceName", serviceName);
        this.podUid = requirePart("podUid", podUid);
        this.bootId = requirePart("bootId", bootId);
        this.podName = requirePart("podName", podName);
        this.sessionId = this.serviceName + ":" + this.podUid + ":" + this.bootId;
    }

    /**
     * 런타임 사실에 맞는 session 을 생성합니다.
     *
     * @param serviceName 서비스명
     * @param podUid Downward API 로 주입된 Pod UID(없으면 null/blank)
     * @param hostname 로컬 대체 식별자이자 Pod 이름
     * @param allowLocalIdentity 로컬 개발 대체 허용 여부
     * @param kubernetesRuntime Kubernetes 안에서 도는지 여부
     * @return session
     */
    public static UiCoordinationSession create(
            final String serviceName,
            final String podUid,
            final String hostname,
            final boolean allowLocalIdentity,
            final boolean kubernetesRuntime
    ) {
        final String bootId = UUID.randomUUID().toString();
        final String podName = hostname == null || hostname.isBlank() ? "localhost" : sanitize(hostname);
        if (podUid != null && !podUid.isBlank()) {
            return new UiCoordinationSession(serviceName, sanitize(podUid), bootId, podName);
        }
        if (kubernetesRuntime) {
            throw new IllegalStateException(
                    "POD_UID is required in a Kubernetes runtime — inject metadata.uid via the Downward API. "
                            + "allow-local-session-identity is ignored here (local development only)");
        }
        if (!allowLocalIdentity) {
            throw new IllegalStateException(
                    "POD_UID is required — inject metadata.uid via the Kubernetes Downward API, "
                            + "or set tc.ui.backend.coordination.allow-local-session-identity=true for local profiles");
        }
        log.warn("UI_COORDINATION_LOCAL_SESSION_IDENTITY. POD_UID 가 없어 hostname 으로 대체합니다. hostname={}", podName);
        return new UiCoordinationSession(serviceName, podName, bootId, podName);
    }

    public String serviceName() {
        return serviceName;
    }

    public String podUid() {
        return podUid;
    }

    public String podName() {
        return podName;
    }

    public String bootId() {
        return bootId;
    }

    public String sessionId() {
        return sessionId;
    }

    public UiMembershipState state() {
        return state.get();
    }

    /**
     * 이 session 이 확정(전이/ACK)을 수행할 수 있는 상태인지 반환합니다.
     *
     * @return {@code ACTIVE_SYNCED} 이면 true
     */
    public boolean active() {
        return state.get() == UiMembershipState.ACTIVE_SYNCED;
    }

    /**
     * membership 상태를 전이합니다.
     *
     * <p>{@code FENCED} 는 프로세스 안에서도 종착 상태다(Plan §8 B22) — 나가는 전이를 거부하지 않으면
     * JVM 내부 호출이 로컬 상태를 되살려 이미 소유권을 잃은 session 이 executor 전이를 통과시킨다.</p>
     *
     * @param next 목표 상태
     * @return 전이했으면 true
     */
    public boolean transitionTo(final UiMembershipState next) {
        Objects.requireNonNull(next, "next is null");
        while (true) {
            final UiMembershipState current = state.get();
            if (current == UiMembershipState.FENCED) {
                if (next != UiMembershipState.FENCED) {
                    log.warn("UI_COORDINATION_FENCED_TRANSITION_REJECTED. sessionId={}, requested={}", sessionId, next);
                }
                return false;
            }
            if (current == next) {
                return false;
            }
            if (state.compareAndSet(current, next)) {
                log.info("UI_COORDINATION_STATE. sessionId={}, from={}, to={}", sessionId, current, next);
                return true;
            }
        }
    }

    /**
     * 이 session 을 영구 fence 합니다.
     */
    public void fence() {
        transitionTo(UiMembershipState.FENCED);
    }

    /**
     * 마지막으로 관측한 전역 revision 을 기록합니다(단조 증가).
     *
     * @param revision 관측 revision
     */
    public void observeGlobalRevision(final long revision) {
        lastObservedGlobalRevision.accumulateAndGet(revision, Math::max);
    }

    public long lastObservedGlobalRevision() {
        return lastObservedGlobalRevision.get();
    }

    private static String requirePart(final String fieldName, final String value) {
        Objects.requireNonNull(value, fieldName + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is blank");
        }
        if (value.indexOf(':') >= 0 || value.indexOf(',') >= 0) {
            throw new IllegalArgumentException(fieldName + " must not contain ':' or ',' : " + value);
        }
        return value;
    }

    private static String sanitize(final String raw) {
        return raw.trim().replace(':', '-').replace(',', '-');
    }
}
