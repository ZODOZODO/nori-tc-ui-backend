package com.nori.tc.ui.adapters.web.controller.support;

import com.nori.tc.ui.adapters.web.dto.response.LiveMapResponse;
import com.nori.tc.ui.core.eqp.LiveMapEquipmentView;
import com.nori.tc.ui.core.eqp.LiveMapPortView;

import java.util.List;

/**
 * live-map core view를 웹 응답 DTO로 변환합니다.
 */
public final class LiveMapResponseSupport {

    private LiveMapResponseSupport() {
    }

    public static LiveMapResponse toResponse(final List<LiveMapEquipmentView> equipmentViews) {
        return new LiveMapResponse(
                equipmentViews.stream()
                        .map(LiveMapResponseSupport::toEquipmentResponse)
                        .toList()
        );
    }

    private static LiveMapResponse.EquipmentResponse toEquipmentResponse(final LiveMapEquipmentView source) {
        return new LiveMapResponse.EquipmentResponse(
                source.eqpKey(),
                source.eqpId(),
                source.eqpName(),
                source.lineValue(),
                source.lineLabel(),
                source.clusterId(),
                source.clusterName(),
                source.equipmentModeLabel(),
                source.status(),
                source.statusLabel(),
                source.portItems().stream().map(LiveMapResponseSupport::toPortResponse).toList(),
                source.tooltipMeta(),
                source.tooltipStep(),
                source.alertMessage()
        );
    }

    private static LiveMapResponse.PortResponse toPortResponse(final LiveMapPortView source) {
        return new LiveMapResponse.PortResponse(
                source.id(),
                source.label(),
                source.carrierId(),
                source.status(),
                source.statusLabel()
        );
    }
}
