package com.nori.tc.ui.core.port.db;

import com.nori.tc.ui.core.eqp.EqpRepairTask;

import java.util.List;

/**
 * EQP 보상 실패 후 남겨두는 repair task 저장 포트입니다.
 */
public interface EqpRepairTaskPort {

    void save(EqpRepairTask repairTask);

    List<EqpRepairTask> findAll();
}
