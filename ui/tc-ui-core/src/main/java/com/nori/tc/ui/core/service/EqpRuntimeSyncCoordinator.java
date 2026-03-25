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

    public void syncUpdate(final EqpManagementSnapshot snapshot, final long timeoutMs) throws Exception {
        runtimeSupport.awaitDualSuccess(UiCommandEventType.EQP_UPDATE, snapshot, timeoutMs);
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
