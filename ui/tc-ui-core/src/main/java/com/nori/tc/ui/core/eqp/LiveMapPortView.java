package com.nori.tc.ui.core.eqp;

import java.util.Objects;

/**
 * live-map 화면에서 사용하는 포트 상태 뷰 모델입니다.
 */
public record LiveMapPortView(
        String id,
        String label,
        String carrierId,
        String status,
        String statusLabel
) {

    public LiveMapPortView {
        id = Objects.requireNonNull(id, "id is null");
        label = Objects.requireNonNull(label, "label is null");
        status = Objects.requireNonNull(status, "status is null");
        statusLabel = Objects.requireNonNull(statusLabel, "statusLabel is null");
    }
}
