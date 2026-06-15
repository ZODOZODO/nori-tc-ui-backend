package com.nori.tc.ui.domain.model;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 PLC 폴링 그룹(읽기 명령) 상세입니다.
 *
 * <p>tc_model_plc_group 1행에 대응하며 model_version_key 에 묶입니다.
 * {@code area} 는 {@link com.nori.tc.ui.domain.eqp.UiEqpPlc} 의 plcProtocol 선례처럼 String 으로
 * 운반하고, 공통 enum(PlcArea) 변환은 영속 경계(JpaModelDetailCommandPort)에서 수행합니다.</p>
 */
public record UiModelPlcGroup(
        long groupKey,
        long modelVersionKey,
        String messageName,
        String area,
        int startAddress,
        int readCount,
        String addressQualifier,
        int pollPeriodMs,
        String description,
        OffsetDateTime updatedAt
) {
}
