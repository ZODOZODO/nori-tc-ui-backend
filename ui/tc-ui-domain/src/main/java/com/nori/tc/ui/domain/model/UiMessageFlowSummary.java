package com.nori.tc.ui.domain.model;

/**
 * UI Backend 내부에서 사용하는 Message Flow 목록 1 row 요약입니다.
 *
 * <p>좌측 사이드바의 Flow 트리 표시에 필요한 최소 필드만 포함합니다.
 * 트리 그룹화 (workflowName 묶음 등) 는 FE 측에서 수행하므로 flat 구조로 유지합니다.</p>
 */
public record UiMessageFlowSummary(
        long flowId,
        String workflowName,
        String messageName,
        String actionName
) {
}
