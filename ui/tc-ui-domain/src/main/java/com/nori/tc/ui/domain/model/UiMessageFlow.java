package com.nori.tc.ui.domain.model;

import java.util.List;

/**
 * UI Backend 내부에서 사용하는 Message Flow 상세 aggregate 입니다.
 *
 * <p>FlowCard 의 4 섹션 (Identity / Trigger / Action / Collect) 을 1 개의 aggregate 로 묶습니다.
 * {@code flowId} 는 {@code tc_model_workflow.workflow_key} 와 동일합니다.</p>
 *
 * <p>Trigger 섹션은 SECS only — Socket 프로토콜인 경우 {@code eventId},
 * {@code transactionId}, {@code workflowFilter} 모두 {@code null} 입니다.</p>
 *
 * <p>{@code workflowFilter} 와 {@code actionDataIndex} 는 raw JSON 문자열 그대로 보관합니다.
 * 검증은 별도 validator 가 담당합니다.</p>
 *
 * <p>Collect 섹션은 두 표현을 동시에 제공합니다:</p>
 * <ul>
 *   <li>{@code dcopItems} — 응답용. Flow 에 매핑된 DCOP item 본체 목록 (read 시 채워짐).</li>
 *   <li>{@code dcopItemKeys} — 요청용. Flow 의 join table binding 갱신 시 사용할 dcop_item_key
 *     배열 (PUT round-trip 입력). 응답 시에도 {@code dcopItems} 의 키 집합과 일치하도록 채워진다.</li>
 * </ul>
 *
 * @param flowId           Workflow PK (= tc_model_workflow.workflow_key)
 * @param workflowName     Identity 섹션 — Workflow 명
 * @param messageName      Identity 섹션 — Message 명
 * @param eventId          Trigger 섹션 — SECS event ID (Socket 일 때 null)
 * @param transactionId    Trigger 섹션 — SECS transaction ID (null = wildcard, SECS only)
 * @param workflowFilter   Trigger 섹션 — workflow_filter raw JSON (SECS only, nullable)
 * @param actionName       Action 섹션 — Action 명
 * @param actionDataIndex  Action 섹션 — action_data_index raw JSON (nullable)
 * @param dcopItems        Collect 섹션 (응답) — Flow 에 매핑된 DCOP item 본체 목록
 * @param dcopItemKeys     Collect 섹션 (요청/응답) — Flow 의 join row dcop_item_key 배열
 */
public record UiMessageFlow(
        long flowId,
        String workflowName,
        String messageName,
        String eventId,
        String transactionId,
        String workflowFilter,
        String actionName,
        String actionDataIndex,
        List<UiModelDcopItem> dcopItems,
        List<Long> dcopItemKeys
) {
}
