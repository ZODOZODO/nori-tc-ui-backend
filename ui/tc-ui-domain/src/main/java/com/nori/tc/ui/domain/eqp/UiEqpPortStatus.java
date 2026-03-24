package com.nori.tc.ui.domain.eqp;

import com.nori.tc.ui.domain.common.eqp.UiCarrierState;
import com.nori.tc.ui.domain.common.eqp.UiCarrierType;
import com.nori.tc.ui.domain.common.eqp.UiPortType;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 포트 상태 스냅샷입니다.
 */
public record UiEqpPortStatus(
        long eqpPortStatusKey,
        long eqpKey,
        String portId,
        UiPortType portType,
        String portState,
        String carrierId,
        UiCarrierType carrierType,
        UiCarrierState carrierState,
        OffsetDateTime updatedAt
) {
}
