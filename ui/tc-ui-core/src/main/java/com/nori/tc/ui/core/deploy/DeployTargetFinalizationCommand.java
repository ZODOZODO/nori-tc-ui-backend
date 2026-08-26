package com.nori.tc.ui.core.deploy;

import java.util.List;
import java.util.Objects;

/**
 * deploy operation 의 DB 확정 payload 입니다(§13.6/§13.7).
 *
 * <p>한 operation 이 영향 폐쇄 그룹에 속한 여러 target 을 함께 확정하므로 target key 를 목록으로
 * 싣습니다. 이 payload 는 원장에 그대로 저장되므로 <b>비민감 값만</b> 담습니다.</p>
 *
 * @param deployKey deploy 식별자
 * @param deployTargetKeys 이 operation 이 확정할 target 목록
 * @param actor 요청자의 비민감 식별 정보
 */
public record DeployTargetFinalizationCommand(
        long deployKey,
        List<Long> deployTargetKeys,
        String actor
) {

    /**
     * 값 계약을 검증하고 불변 목록으로 정규화합니다.
     */
    public DeployTargetFinalizationCommand {
        if (deployKey <= 0L) {
            throw new IllegalArgumentException("deployKey must be greater than 0");
        }
        deployTargetKeys = List.copyOf(Objects.requireNonNull(deployTargetKeys, "deployTargetKeys is null"));
        if (deployTargetKeys.isEmpty()) {
            throw new IllegalArgumentException("deployTargetKeys is empty");
        }
    }
}
