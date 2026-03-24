package com.nori.tc.ui.domain.eqp;

import com.nori.tc.ui.domain.common.model.UiProtocolType;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 설비 기본 스냅샷입니다.
 */
public record UiEqp(
        Long eqpKey,
        String eqpId,
        UiProtocolType commInterface,
        String commMode,
        boolean isDev,
        Integer routePartition,
        String eqpIp,
        int eqpPort,
        long modelVersionKey,
        String appliedParamVersion,
        Long gatewayJarKey,
        Long businessJarKey,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {
}
