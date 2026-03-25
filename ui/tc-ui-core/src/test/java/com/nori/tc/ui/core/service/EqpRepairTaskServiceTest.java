package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.eqp.EqpRepairAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EqpRepairTaskServiceTest {

    @Test
    void recordUsesStableTaskIdForSameRepairAction() {
        final InMemoryEqpRepairTaskStore store = new InMemoryEqpRepairTaskStore();
        final EqpRepairTaskService service = new EqpRepairTaskService(store);

        service.record("EQP-01", "EQP_UPDATE", EqpRepairAction.RUNTIME_UPDATE, "ERR-1", "first");
        service.record("EQP-01", "EQP_UPDATE", EqpRepairAction.RUNTIME_UPDATE, "ERR-2", "second");

        final List<com.nori.tc.ui.core.eqp.EqpRepairTask> tasks = store.findAll();
        assertEquals(1, tasks.size());
        assertEquals("ERR-2", tasks.getFirst().errorCode());
        assertEquals("second", tasks.getFirst().errorMessage());
        assertEquals("EQP-01:EQP_UPDATE:RUNTIME_UPDATE", tasks.getFirst().taskId());
    }
}
