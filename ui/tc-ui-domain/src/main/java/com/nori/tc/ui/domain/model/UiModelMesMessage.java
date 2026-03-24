package com.nori.tc.ui.domain.model;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 MES 메시지 상세입니다.
 */
public record UiModelMesMessage(
        long mesMsgKey,
        long modelVersionKey,
        String mesMsgName,
        String description,
        String dataIndex,
        OffsetDateTime updatedAt
) {
}
