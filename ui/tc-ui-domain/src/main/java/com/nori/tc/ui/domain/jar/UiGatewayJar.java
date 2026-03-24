package com.nori.tc.ui.domain.jar;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 Gateway JAR 메타입니다.
 */
public record UiGatewayJar(
        long gatewayJarKey,
        String jarFileName,
        byte[] jarFile,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {
}
