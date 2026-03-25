package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.eqp.EqpRepairTask;
import com.nori.tc.ui.core.port.db.EqpRepairTaskPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 기본 repair task 저장소입니다.
 *
 * <p>현재 UI backend에는 repair task 전용 영속 어댑터가 없으므로
 * 최소한의 운영 추적을 위해 인메모리로 보관합니다.</p>
 */
@Component
final class InMemoryEqpRepairTaskStore implements EqpRepairTaskPort {

    private final CopyOnWriteArrayList<EqpRepairTask> tasks = new CopyOnWriteArrayList<>();

    @Override
    public void save(final EqpRepairTask repairTask) {
        tasks.add(repairTask);
    }

    @Override
    public List<EqpRepairTask> findAll() {
        return List.copyOf(tasks);
    }
}
