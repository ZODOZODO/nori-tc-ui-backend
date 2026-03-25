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
import java.util.UUID;

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
        final EqpRepairTask task = new EqpRepairTask(
                UUID.randomUUID().toString(),
                Objects.requireNonNull(eqpId, "eqpId is null"),
                Objects.requireNonNull(commandType, "commandType is null"),
                Objects.requireNonNull(repairAction, "repairAction is null"),
                normalizedText(errorCode).orElse(null),
                normalizedText(errorMessage).orElse(null),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        repairTaskPort.save(task);
        log.error("EQP repair task 기록. taskId={}, eqpId={}, commandType={}, repairAction={}, errorCode={}, errorMessage={}",
                task.taskId(), task.eqpId(), task.commandType(), task.repairAction(), task.errorCode(), task.errorMessage());
    }

    public List<EqpRepairTask> findAll() {
        return repairTaskPort.findAll();
    }

    private static Optional<String> normalizedText(final String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.trim());
    }
}
