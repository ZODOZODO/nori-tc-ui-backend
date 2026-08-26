package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.deploy.DeployExecutionTarget;

import java.util.List;
import java.util.Objects;

/**
 * 한 operation 으로 함께 접수할 deploy target 묶음입니다(§13.6 / Plan D9).
 *
 * <p>{@code resourceKeys} 는 그룹에 속한 target 들의 영향 집합 합집합이며 lexicographic 정렬입니다.
 * 정렬은 계약입니다 — 여러 리소스를 잠그는 순서가 operation 마다 다르면 서로를 기다리는 교착이
 * 생깁니다(§18.1).</p>
 *
 * @param targets 그룹에 속한 target(deployTargetKey 오름차순)
 * @param resourceKeys 그룹이 잠글 리소스 키(정렬됨)
 */
public record DeployOperationGroup(
        List<DeployExecutionTarget> targets,
        List<String> resourceKeys
) {

    /**
     * 값 계약을 검증하고 불변 목록으로 정규화합니다.
     */
    public DeployOperationGroup {
        targets = List.copyOf(Objects.requireNonNull(targets, "targets is null"));
        resourceKeys = List.copyOf(Objects.requireNonNull(resourceKeys, "resourceKeys is null"));
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("targets is empty");
        }
        if (resourceKeys.isEmpty()) {
            throw new IllegalArgumentException("resourceKeys is empty");
        }
    }

    /**
     * 그룹의 대표 target key 를 반환합니다.
     *
     * <p>execution/idempotency key 를 결정적으로 만들기 위한 기준입니다 — 같은 그룹이면 항상 같은
     * 값이 나와야 합니다.</p>
     *
     * @return 그룹 내 최소 {@code deployTargetKey}
     */
    public long representativeTargetKey() {
        return targets.get(0).target().deployTargetKey();
    }
}
