package com.nori.tc.ui.domain.model;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 MDF 상세입니다.
 */
public record UiModelMdf(
        long mdfKey,
        long modelVersionKey,
        String mdfName,
        byte[] mdfFile,
        OffsetDateTime updatedAt
) {
}
