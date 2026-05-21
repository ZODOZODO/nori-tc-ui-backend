package com.nori.tc.ui.adapters.web.dto.response;

import com.nori.tc.ui.domain.model.UiMessageFlowSummary;

import java.util.List;

/**
 * Message Flow 목록 응답 DTO 입니다.
 *
 * <p>GET {@code /api/model/{modelVersionKey}/flows} 의 응답 본문입니다.
 * Flat 구조 — 트리 그룹화 (workflowName 묶음) 는 FE 가 담당합니다.</p>
 *
 * @param items Flow 요약 row 목록 (message_flow_key ASC)
 */
public record ModelFlowListResponse(
        List<ModelFlowSummaryItem> items
) {

    /**
     * 도메인 summary 목록으로부터 응답 DTO 를 생성합니다.
     *
     * @param summaries 도메인 summary 목록
     * @return 응답 DTO
     */
    public static ModelFlowListResponse from(final List<UiMessageFlowSummary> summaries) {
        final List<ModelFlowSummaryItem> mapped = summaries.stream()
                .map(s -> new ModelFlowSummaryItem(s.flowId(), s.workflowName(), s.messageName(), s.actionName()))
                .toList();
        return new ModelFlowListResponse(mapped);
    }

    /**
     * 목록 1 행 항목입니다.
     *
     * @param flowId       Message Flow PK
     * @param workflowName Identity 섹션 — Workflow 명
     * @param messageName  Identity 섹션 — Message 명
     * @param actionName   Action 섹션 — Action 명
     */
    public record ModelFlowSummaryItem(
            long flowId,
            String workflowName,
            String messageName,
            String actionName
    ) {
    }
}
