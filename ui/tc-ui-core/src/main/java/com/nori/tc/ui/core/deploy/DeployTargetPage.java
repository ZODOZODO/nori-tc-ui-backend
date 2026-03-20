package com.nori.tc.ui.core.deploy;

import java.util.List;

/**
 * deploy target 페이지 결과입니다.
 */
public record DeployTargetPage(
        long deployKey,
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<DeployTargetSnapshot> targets
) {
}
