package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.eqp.EqpRepairAction;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * EQP 보상 흐름과 repair task 기록을 담당합니다.
 */
@Service
public class EqpCompensationCoordinator {

    private final EqpManagementRuntimeSupport runtimeSupport;
    private final EqpRepairTaskService repairTaskService;

    public EqpCompensationCoordinator(
            final EqpManagementRuntimeSupport runtimeSupport,
            final EqpRepairTaskService repairTaskService
    ) {
        this.runtimeSupport = Objects.requireNonNull(runtimeSupport, "runtimeSupport is null");
        this.repairTaskService = Objects.requireNonNull(repairTaskService, "repairTaskService is null");
    }

    public void compensateCreateFailure(
            final EqpManagementSnapshot createdSnapshot,
            final long timeoutMs
    ) {
        recordIfFailed(
                createdSnapshot.eqp().eqpId(),
                "EQP_CREATE",
                EqpRepairAction.RUNTIME_DELETE,
                runtimeSupport.tryRuntimeDelete(createdSnapshot, timeoutMs)
        );
        recordIfFailed(
                createdSnapshot.eqp().eqpId(),
                "EQP_CREATE",
                EqpRepairAction.DB_DELETE,
                runtimeSupport.tryDelete(createdSnapshot.eqp().eqpId())
        );
    }

    public void compensateUpdateFailure(
            final EqpManagementSnapshot previousSnapshot,
            final long timeoutMs,
            final boolean jarReloadRequired
    ) {
        recordIfFailed(
                previousSnapshot.eqp().eqpId(),
                "EQP_UPDATE",
                EqpRepairAction.DB_RESTORE,
                runtimeSupport.tryRestore(previousSnapshot)
        );
        recordIfFailed(
                previousSnapshot.eqp().eqpId(),
                "EQP_UPDATE",
                EqpRepairAction.RUNTIME_UPDATE,
                runtimeSupport.tryRuntimeUpdate(previousSnapshot, timeoutMs)
        );
        if (jarReloadRequired) {
            recordIfFailed(
                    previousSnapshot.eqp().eqpId(),
                    "EQP_UPDATE",
                    EqpRepairAction.JAR_RELOAD,
                    runtimeSupport.tryJarReload(previousSnapshot, timeoutMs)
            );
        }
    }

    public void compensateDeleteFailure(
            final EqpManagementSnapshot deletedSnapshot,
            final long timeoutMs,
            final boolean jarReloadRequired
    ) {
        recordIfFailed(
                deletedSnapshot.eqp().eqpId(),
                "EQP_DELETE",
                EqpRepairAction.DB_RESTORE,
                runtimeSupport.tryRestore(deletedSnapshot)
        );
        recordIfFailed(
                deletedSnapshot.eqp().eqpId(),
                "EQP_DELETE",
                EqpRepairAction.RUNTIME_CREATE,
                runtimeSupport.tryRuntimeCreate(deletedSnapshot, timeoutMs)
        );
        if (jarReloadRequired) {
            recordIfFailed(
                    deletedSnapshot.eqp().eqpId(),
                    "EQP_DELETE",
                    EqpRepairAction.JAR_RELOAD,
                    runtimeSupport.tryJarReload(deletedSnapshot, timeoutMs)
            );
        }
    }

    private void recordIfFailed(
            final String eqpId,
            final String commandType,
            final EqpRepairAction repairAction,
            final EqpRepairAttemptResult attemptResult
    ) {
        if (attemptResult.success()) {
            return;
        }
        repairTaskService.record(
                eqpId,
                commandType,
                repairAction,
                attemptResult.errorCode(),
                attemptResult.errorMessage()
        );
    }
}
