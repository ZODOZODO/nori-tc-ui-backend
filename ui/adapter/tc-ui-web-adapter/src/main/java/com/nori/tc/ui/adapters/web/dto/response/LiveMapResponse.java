package com.nori.tc.ui.adapters.web.dto.response;

import java.util.List;

/**
 * live-map 조회 응답 DTO 입니다.
 */
public record LiveMapResponse(
        List<EquipmentResponse> equipmentItems
) {

    public record EquipmentResponse(
            String eqpId,
            String eqpName,
            String lineValue,
            String lineLabel,
            String clusterId,
            String clusterName,
            String equipmentModeLabel,
            String status,
            String statusLabel,
            List<PortResponse> portItems,
            String tooltipMeta,
            String tooltipStep,
            String alertMessage
    ) {
    }

    public record PortResponse(
            String id,
            String label,
            String status,
            String statusLabel
    ) {
    }
}
