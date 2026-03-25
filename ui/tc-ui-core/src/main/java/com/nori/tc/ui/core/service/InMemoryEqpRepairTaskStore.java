package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.eqp.EqpRepairTask;
import com.nori.tc.ui.core.port.db.EqpRepairTaskPort;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 기본 repair task 저장소입니다.
 *
 * <p>테스트 환경에서도 repair idempotency 의미를 유지하기 위해 taskId 기준으로 덮어씁니다.</p>
 */
final class InMemoryEqpRepairTaskStore implements EqpRepairTaskPort {

    private final Map<String, EqpRepairTask> tasks = new ConcurrentHashMap<>();

    @Override
    public void save(final EqpRepairTask repairTask) {
        tasks.put(repairTask.taskId(), repairTask);
    }

    @Override
    public List<EqpRepairTask> findAll() {
        return tasks.values().stream()
                .sorted((left, right) -> left.createdAt().compareTo(right.createdAt()))
                .toList();
    }
}
