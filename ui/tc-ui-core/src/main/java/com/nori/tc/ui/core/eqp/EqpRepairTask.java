package com.nori.tc.ui.core.eqp;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * EQP 보상 실패 후 후속 복구가 필요한 작업을 표현합니다.
 */
public record EqpRepairTask(
        String taskId,
        String eqpId,
        String commandType,
        EqpRepairAction repairAction,
        String errorCode,
        String errorMessage,
        OffsetDateTime createdAt
) {

    public EqpRepairTask {
        Objects.requireNonNull(taskId, "taskId is null");
        Objects.requireNonNull(eqpId, "eqpId is null");
        Objects.requireNonNull(commandType, "commandType is null");
        Objects.requireNonNull(repairAction, "repairAction is null");
        Objects.requireNonNull(createdAt, "createdAt is null");
    }
}
