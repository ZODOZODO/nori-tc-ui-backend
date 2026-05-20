package com.nori.tc.ui.adapters.web.dto.request;

import com.nori.tc.ui.domain.model.UiMessageFlow;

import java.util.List;

/**
 * Message Flow 통합 갱신 요청 DTO 입니다.
 *
 * <p>PUT {@code /api/model/{modelVersionKey}/flows/{flowId}} 의 본문 형식입니다.
 * Identity + Trigger + Action 3 섹션을 한 요청으로 갱신합니다 — Collect (DCOP item)
 * 저장은 기존 {@code PUT /details/dcop-itemes} 흐름을 그대로 사용합니다.</p>
 *
 * <p>{@code workflowFilter} 와 {@code actionDataIndex} 는 raw JSON 문자열로 받습니다.
 * Controller 가 호출 전 {@link com.nori.tc.ui.adapters.web.controller.support.ModelDetailWorkflowJsonSupport}
 * 로 스키마 검증을 수행하며, 비즈니스 검증은 service 가 담당합니다.</p>
 *
 * @param workflowName    Identity 섹션 — Workflow 명
 * @param messageName     Identity 섹션 — Message 명
 * @param eventId         Trigger 섹션 — SECS event ID (Socket 일 때 null)
 * @param transactionId   Trigger 섹션 — SECS transaction ID (null = wildcard)
 * @param workflowFilter  Trigger 섹션 — workflow_filter raw JSON (nullable)
 * @param actionName      Action 섹션 — Action 명
 * @param actionDataIndex Action 섹션 — action_data_index raw JSON (nullable)
 */
public record ModelFlowUpdateRequest(
        String workflowName,
        String messageName,
        String eventId,
        String transactionId,
        String workflowFilter,
        String actionName,
        String actionDataIndex
) {

    /**
     * path 의 {@code flowId} 를 결합해 service 가 받는 도메인 aggregate 로 변환합니다.
     *
     * <p>{@code dcopItems} 는 본 PUT 의 책임이 아니므로 빈 리스트로 두고, service /
     * port 가 update 시 무시합니다 — Plan D4 (DCOP CRUD 는 기존 경로 재사용).</p>
     *
     * @param flowId path 에서 추출한 Workflow PK
     * @return 도메인 aggregate
     */
    public UiMessageFlow toDomain(final long flowId) {
        return new UiMessageFlow(
                flowId,
                workflowName,
                messageName,
                eventId,
                transactionId,
                workflowFilter,
                actionName,
                actionDataIndex,
                List.of()
        );
    }
}
