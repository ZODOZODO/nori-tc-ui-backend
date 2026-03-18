package com.nori.tc.ui.core.port.db;

import com.nori.tc.ui.core.eqp.EqpManagementOptions;

/**
 * EQP 관리 화면 옵션 조회 포트입니다.
 */
public interface EqpOptionsQueryPort {

    /**
     * 관리 화면 드롭다운 옵션을 조회합니다.
     *
     * @return 관리 옵션 묶음
     */
    EqpManagementOptions loadOptions();
}
