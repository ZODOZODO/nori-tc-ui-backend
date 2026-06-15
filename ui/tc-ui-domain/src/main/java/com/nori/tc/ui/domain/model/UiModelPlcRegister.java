package com.nori.tc.ui.domain.model;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 PLC 레지스터(디코드 변수) 상세입니다.
 *
 * <p>tc_model_plc_register 1행에 대응하며 group_key 에 묶입니다. UI 의 plc-register 노드는
 * 모델 전체 레지스터를 한 평면 테이블로 보여주므로, 소속 그룹을 식별하기 위해
 * {@code groupMessageName}(tc_model_plc_group.message_name) 을 함께 운반합니다.</p>
 *
 * <p>{@code dataType} 은 {@link com.nori.tc.ui.domain.eqp.UiEqpPlc} 선례처럼 String 으로 운반하고,
 * 공통 enum(PlcDataType) 변환은 영속 경계(JpaModelDetailCommandPort)에서 수행합니다.
 * modifier 컬럼은 해당 안 되면 null 입니다.</p>
 */
public record UiModelPlcRegister(
        long registerKey,
        long groupKey,
        String groupMessageName,
        int wordOffset,
        String variableName,
        String dataType,
        Integer length,
        Integer bitIndex,
        Integer byteIndex,
        String byteOrder,
        String wordOrder,
        Double scale,
        Boolean bcdSigned,
        String charEncoding,
        String description,
        OffsetDateTime updatedAt
) {
}
