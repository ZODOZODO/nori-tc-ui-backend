package com.nori.tc.ui.domain.eqp;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 설비 파라미터 버전 메타입니다.
 */
public record UiEqpParamVersion(
        long eqpParamVersionKey,
        long eqpKey,
        String paramVersion,
        String versionDescription,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {
}
