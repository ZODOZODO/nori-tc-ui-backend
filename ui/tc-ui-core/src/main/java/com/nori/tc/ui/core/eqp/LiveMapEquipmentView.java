package com.nori.tc.ui.core.eqp;

import java.util.List;
import java.util.Objects;

/**
 * live-map 화면에서 사용하는 설비 뷰 모델입니다.
 */
public record LiveMapEquipmentView(
        Long eqpKey,
        String eqpId,
        String eqpName,
        String lineValue,
        String lineLabel,
        String clusterId,
        String clusterName,
        String equipmentModeLabel,
        String status,
        String statusLabel,
        List<LiveMapPortView> portItems,
        String tooltipMeta,
        String tooltipStep,
        String alertMessage
) {

    public LiveMapEquipmentView {
        eqpKey = Objects.requireNonNull(eqpKey, "eqpKey is null");
        eqpId = Objects.requireNonNull(eqpId, "eqpId is null");
        eqpName = Objects.requireNonNull(eqpName, "eqpName is null");
        lineValue = Objects.requireNonNull(lineValue, "lineValue is null");
        lineLabel = Objects.requireNonNull(lineLabel, "lineLabel is null");
        clusterId = Objects.requireNonNull(clusterId, "clusterId is null");
        clusterName = Objects.requireNonNull(clusterName, "clusterName is null");
        equipmentModeLabel = equipmentModeLabel == null || equipmentModeLabel.isBlank() ? "-" : equipmentModeLabel;
        status = Objects.requireNonNull(status, "status is null");
        statusLabel = Objects.requireNonNull(statusLabel, "statusLabel is null");
        portItems = portItems == null ? List.of() : List.copyOf(portItems);
        tooltipMeta = tooltipMeta == null || tooltipMeta.isBlank() ? "포트 정보가 없습니다." : tooltipMeta;
        tooltipStep = tooltipStep == null ? "" : tooltipStep;
    }
}
