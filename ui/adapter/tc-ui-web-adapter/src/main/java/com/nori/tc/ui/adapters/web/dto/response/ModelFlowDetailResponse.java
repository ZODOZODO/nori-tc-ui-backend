package com.nori.tc.ui.adapters.web.dto.response;

import com.nori.tc.ui.domain.model.UiMessageFlow;
import com.nori.tc.ui.domain.model.UiModelDcopItem;

import java.util.List;

/**
 * Message Flow 4 섹션 상세 응답 DTO 입니다.
 *
 * <p>GET {@code /api/model/{modelVersionKey}/flows/{flowId}} 의 응답 본문이며,
 * PUT 응답도 동일 형식입니다 — FE 가 한 번의 round-trip 으로 4 섹션 모두를 받아
 * 화면 상태를 갱신할 수 있도록 합니다.</p>
 *
 * <p>{@code workflowFilter} 와 {@code actionDataIndex} 는 raw JSON 문자열로 노출 — FE 가
 * 시각화 / 편집기에서 동일 형태로 다룹니다 (Plan D9).</p>
 *
 * @param flowId          Workflow PK
 * @param workflowName    Identity 섹션 — Workflow 명
 * @param messageName     Identity 섹션 — Message 명
 * @param eventId         Trigger 섹션 — SECS event ID (Socket 일 때 null)
 * @param transactionId   Trigger 섹션 — SECS transaction ID (null = wildcard)
 * @param workflowFilter  Trigger 섹션 — workflow_filter raw JSON (nullable)
 * @param actionName      Action 섹션 — Action 명
 * @param actionDataIndex Action 섹션 — action_data_index raw JSON (nullable)
 * @param dcopItems       Collect 섹션 — Flow 에 매핑된 DCOP item 목록
 */
public record ModelFlowDetailResponse(
        long flowId,
        String workflowName,
        String messageName,
        String eventId,
        String transactionId,
        String workflowFilter,
        String actionName,
        String actionDataIndex,
        List<UiModelDcopItem> dcopItems
) {

    /**
     * 도메인 aggregate 로부터 응답 DTO 를 생성합니다.
     *
     * @param flow 도메인 aggregate
     * @return 응답 DTO
     */
    public static ModelFlowDetailResponse from(final UiMessageFlow flow) {
        return new ModelFlowDetailResponse(
                flow.flowId(),
                flow.workflowName(),
                flow.messageName(),
                flow.eventId(),
                flow.transactionId(),
                flow.workflowFilter(),
                flow.actionName(),
                flow.actionDataIndex(),
                flow.dcopItems() == null ? List.of() : flow.dcopItems()
        );
    }
}
