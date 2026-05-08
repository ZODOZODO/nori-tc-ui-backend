package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.model.UiCommandEventType;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * EQP runtime 동기화/종료/JAR reload 호출을 분리합니다.
 */
@Service
public class EqpRuntimeSyncCoordinator {

    private final EqpManagementRuntimeSupport runtimeSupport;

    public EqpRuntimeSyncCoordinator(final EqpManagementRuntimeSupport runtimeSupport) {
        this.runtimeSupport = Objects.requireNonNull(runtimeSupport, "runtimeSupport is null");
    }

    public void syncCreate(final EqpManagementSnapshot snapshot, final long timeoutMs) throws Exception {
        runtimeSupport.awaitDualSuccess(UiCommandEventType.EQP_CREATE, snapshot, timeoutMs);
    }

    /**
     * EQP update를 Gateway/Business에 동기화합니다.
     *
     * <p>route_partition이 변경된 경우 옛 partition에 {@code EQP_PARTITION_RELEASE}를 먼저 best-effort로 발행하고,
     * 그 후 새 partition에 {@code EQP_UPDATE}를 발행합니다. release 발행 실패는 warn 로그만 남기고 update를 계속 진행합니다.</p>
     *
     * @param existingSnapshot 변경 전 snapshot (옛 route_partition 추출용)
     * @param updatedSnapshot 변경 후 snapshot (새 route_partition + 발행 데이터)
     * @param timeoutMs Gateway/Business 응답 대기 timeout(ms)
     */
    public void syncUpdate(
            final EqpManagementSnapshot existingSnapshot,
            final EqpManagementSnapshot updatedSnapshot,
            final long timeoutMs
    ) throws Exception {
        publishPartitionReleaseIfChanged(existingSnapshot, updatedSnapshot);
        runtimeSupport.awaitDualSuccess(UiCommandEventType.EQP_UPDATE, updatedSnapshot, timeoutMs);
    }

    /**
     * 옛 → 새 route_partition이 다른 경우에만 옛 partition으로 RELEASE 메시지를 발행합니다.
     *
     * <p>옛 routePartition이 null이거나 음수이면 발행 대상이 없으므로 skip합니다.</p>
     */
    private void publishPartitionReleaseIfChanged(
            final EqpManagementSnapshot existingSnapshot,
            final EqpManagementSnapshot updatedSnapshot
    ) {
        if (existingSnapshot == null || updatedSnapshot == null) {
            return;
        }
        final Integer oldPartition = existingSnapshot.eqp().routePartition();
        final Integer newPartition = updatedSnapshot.eqp().routePartition();
        if (oldPartition == null || oldPartition < 0) {
            return;
        }
        if (newPartition != null && oldPartition.intValue() == newPartition.intValue()) {
            return;
        }
        runtimeSupport.publishPartitionReleaseQuietly(updatedSnapshot, oldPartition);
    }

    public void syncDelete(final EqpManagementSnapshot snapshot, final long timeoutMs) throws Exception {
        runtimeSupport.awaitDualSuccess(UiCommandEventType.EQP_DELETE, snapshot, timeoutMs);
    }

    public void endLifecycle(final EqpManagementSnapshot snapshot, final long timeoutMs) throws Exception {
        runtimeSupport.awaitLifecycleSuccess(UiCommandEventType.EQP_END, snapshot, timeoutMs);
    }

    public void reloadJar(final EqpManagementSnapshot snapshot, final long timeoutMs) throws Exception {
        runtimeSupport.awaitJarReloadSuccess(snapshot, timeoutMs);
    }

    public boolean isAlreadyStopped(final EqpManagementSnapshot snapshot) {
        return runtimeSupport.isAlreadyStopped(snapshot);
    }
}
