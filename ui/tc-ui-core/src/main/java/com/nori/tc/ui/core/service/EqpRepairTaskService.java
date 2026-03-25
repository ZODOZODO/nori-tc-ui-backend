package com.nori.tc.ui.core.service;
import com.nori.tc.ui.core.eqp.EqpRepairAction;
import com.nori.tc.ui.core.eqp.EqpRepairTask;
import com.nori.tc.ui.core.port.db.EqpRepairTaskPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * EQP 보상 실패를 repair task로 기록합니다.
 */
@Service
public class EqpRepairTaskService {

    private static final Logger log = LoggerFactory.getLogger(EqpRepairTaskService.class);

    private final EqpRepairTaskPort repairTaskPort;

    public EqpRepairTaskService(final EqpRepairTaskPort repairTaskPort) {
        this.repairTaskPort = Objects.requireNonNull(repairTaskPort, "repairTaskPort is null");
    }

    public void record(
            final String eqpId,
            final String commandType,
            final EqpRepairAction repairAction,
            final String errorCode,
            final String errorMessage
    ) {
        final String normalizedEqpId = Objects.requireNonNull(eqpId, "eqpId is null");
        final String normalizedCommandType = Objects.requireNonNull(commandType, "commandType is null");
        final EqpRepairAction normalizedRepairAction = Objects.requireNonNull(repairAction, "repairAction is null");
        final EqpRepairTask task = new EqpRepairTask(
                buildTaskId(normalizedEqpId, normalizedCommandType, normalizedRepairAction),
                normalizedEqpId,
                normalizedCommandType,
                normalizedRepairAction,
                normalizedText(errorCode).orElse(null),
                normalizedText(errorMessage).orElse(null),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        repairTaskPort.save(task);
        log.error("EQP repair task 기록. failureCode=UI_EQP_REPAIR_TASK_RECORDED, failureCategory=COMPENSATION, "
                        + "failureSeverity=ERROR, taskId={}, eqpId={}, commandType={}, repairAction={}, errorCode={}, errorMessage={}",
                task.taskId(),
                task.eqpId(),
                task.commandType(),
                task.repairAction(),
                task.errorCode(),
                task.errorMessage());
    }

    public List<EqpRepairTask> findAll() {
        return repairTaskPort.findAll();
    }

    public int backlogCount() {
        return repairTaskPort.count();
    }

    private static Optional<String> normalizedText(final String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.trim());
    }

    /**
     * 같은 보상 작업이 반복 기록돼도 하나의 durable task로 수렴되도록 taskId를 안정적으로 생성합니다.
     */
    private static String buildTaskId(
            final String eqpId,
            final String commandType,
            final EqpRepairAction repairAction
    ) {
        return (eqpId + ":" + commandType + ":" + repairAction.name()).replace(' ', '_');
    }
}
