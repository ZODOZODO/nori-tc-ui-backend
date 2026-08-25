package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiMembershipState;
import com.nori.tc.ui.core.coordination.port.UiCoordinationMembershipPort;
import com.nori.tc.ui.core.coordination.port.UiCoordinationProbePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * membership 등록 / heartbeat / 기동 복구를 담당하는 조정 런타임입니다(§9 / §13.4 / §13.11).
 *
 * <p>{@link SmartLifecycle} phase 를 음수로 두어 Kafka listener·web 트래픽보다 먼저 시작합니다 —
 * sync 를 마치기 전에 요청을 받으면 원장을 모르는 상태로 mutation 을 접수하게 됩니다.</p>
 *
 * <p>heartbeat 가 <b>불확실</b>하면 즉시 self-fence 합니다. 누적 실패 임계로 유예를 두면 그 사이
 * lease 는 이미 만료돼 다른 Pod 가 executor 가 되고, 두 Pod 가 같은 operation 을 전이시킵니다.</p>
 */
public final class UiCoordinationRuntime implements SmartLifecycle, UiCoordinationStatus {

    private static final Logger log = LoggerFactory.getLogger(UiCoordinationRuntime.class);

    /** Redis 연결(phase 0)보다 먼저 멈추지 않도록, 시작은 앞당기고 종료는 늦춥니다. */
    private static final int LIFECYCLE_PHASE = -100;

    private final UiCoordinationSession session;
    private final UiCoordinationProperties properties;
    private final UiCoordinationMembershipPort membershipPort;
    private final UiCoordinationProbePort probePort;
    private final UiOperationReconciler reconciler;
    private final UiScenarioCatalogGuard scenarioCatalogGuard;
    private final UiPermissionCatalogGuard permissionCatalogGuard;
    private final UiCoordinationMetrics metrics;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScheduledExecutorService scheduler;
    private volatile String lastFenceReason;

    /**
     * 런타임을 만듭니다.
     *
     * @param session 이 Pod 의 session
     * @param properties 조정 설정
     * @param membershipPort membership 포트
     * @param probePort 원장 검증 포트
     * @param reconciler operation reconciler
     * @param scenarioCatalogGuard scenario catalog 게이트
     * @param permissionCatalogGuard permission catalog 게이트
     * @param metrics 조정 metric
     */
    public UiCoordinationRuntime(
            final UiCoordinationSession session,
            final UiCoordinationProperties properties,
            final UiCoordinationMembershipPort membershipPort,
            final UiCoordinationProbePort probePort,
            final UiOperationReconciler reconciler,
            final UiScenarioCatalogGuard scenarioCatalogGuard,
            final UiPermissionCatalogGuard permissionCatalogGuard,
            final UiCoordinationMetrics metrics
    ) {
        this.session = Objects.requireNonNull(session, "session is null");
        this.properties = Objects.requireNonNull(properties, "properties is null");
        this.membershipPort = Objects.requireNonNull(membershipPort, "membershipPort is null");
        this.probePort = Objects.requireNonNull(probePort, "probePort is null");
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler is null");
        this.scenarioCatalogGuard = Objects.requireNonNull(scenarioCatalogGuard, "scenarioCatalogGuard is null");
        this.permissionCatalogGuard = Objects.requireNonNull(permissionCatalogGuard, "permissionCatalogGuard is null");
        this.metrics = Objects.requireNonNull(metrics, "metrics is null");
    }

    @Override
    public int getPhase() {
        return LIFECYCLE_PHASE;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        final long now = System.currentTimeMillis();
        final UiCoordinationMembershipPort.Result registered = membershipPort.register(
                session.sessionId(),
                session.podName(),
                properties.getProtocolVersion(),
                UiMembershipState.JOINING,
                properties.getHeartbeatTtlMs(),
                now);
        if (registered == UiCoordinationMembershipPort.Result.FENCED) {
            throw new IllegalStateException(
                    "이 sessionId 는 TTL 이 만료된 이력이 있어 되살릴 수 없습니다 — 새 bootId 로 재기동해야 합니다. "
                            + "sessionId=" + session.sessionId());
        }

        if (!probePort.verifyAtomicScriptReadWrite()) {
            throw new IllegalStateException("조정 원장의 atomic script 읽기/쓰기 검증에 실패했습니다.");
        }

        reconciler.reconcileAll();
        session.transitionTo(UiMembershipState.ACTIVE_SYNCED);
        heartbeatOnce();

        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "ui-coordination-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(
                this::tick,
                properties.getHeartbeatIntervalMs(),
                properties.getHeartbeatIntervalMs(),
                TimeUnit.MILLISECONDS);

        log.info("UI_COORDINATION_STARTED. sessionId={}, state={}", session.sessionId(), session.state());
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        final ScheduledExecutorService current = scheduler;
        if (current != null) {
            current.shutdownNow();
        }
        log.info("UI_COORDINATION_STOPPED. sessionId={}", session.sessionId());
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void tick() {
        try {
            heartbeatOnce();
            if (session.active()) {
                reconciler.reconcileAll();
            }
        } catch (RuntimeException ex) {
            log.error("UI_COORDINATION_TICK_FAILED. sessionId={}", session.sessionId(), ex);
        }
    }

    private void heartbeatOnce() {
        final long now = System.currentTimeMillis();
        final UiCoordinationMembershipPort.Result result;
        try {
            result = membershipPort.renew(
                    session.sessionId(),
                    properties.getProtocolVersion(),
                    session.state(),
                    session.lastObservedGlobalRevision(),
                    properties.getHeartbeatTtlMs(),
                    now);
        } catch (RuntimeException ex) {
            fence("HEARTBEAT_UNCERTAIN", ex);
            return;
        }
        if (result == UiCoordinationMembershipPort.Result.FENCED) {
            fence("HEARTBEAT_FENCED", null);
        }
    }

    private void fence(final String reason, final RuntimeException cause) {
        lastFenceReason = reason;
        session.fence();
        log.error("UI_COORDINATION_SELF_FENCED. sessionId={}, reason={}", session.sessionId(), reason, cause);
    }

    @Override
    public boolean coordinationEnabled() {
        return true;
    }

    @Override
    public UiMembershipState membershipState() {
        return session.state();
    }

    @Override
    public boolean readyForMutation() {
        if (!session.active()) {
            return false;
        }
        if (reconciler.backlogExceeded()) {
            return false;
        }
        if (!scenarioCatalogGuard.inSync() || !permissionCatalogGuard.inSync()) {
            return false;
        }
        try {
            return probePort.clusterEpochUnchanged() && probePort.verifyAtomicScriptReadWrite();
        } catch (RuntimeException ex) {
            log.error("UI_COORDINATION_PROBE_FAILED. sessionId={}", session.sessionId(), ex);
            return false;
        }
    }

    @Override
    public Map<String, Object> details() {
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("coordinationEnabled", true);
        details.put("sessionId", session.sessionId());
        details.put("membershipState", session.state().name());
        details.put("recoveryBacklog", metrics.recoveryBacklog());
        details.put("scenarioCatalogInSync", scenarioCatalogGuard.inSync());
        details.put("scenarioCatalogRevision", scenarioCatalogGuard.headRevision());
        details.put("permissionCatalogInSync", permissionCatalogGuard.inSync());
        details.put("permissionCatalogRevision", permissionCatalogGuard.headRevision());
        if (lastFenceReason != null) {
            details.put("lastFenceReason", lastFenceReason);
        }
        return details;
    }
}
