package com.nori.tc.ui.domain.model;

/**
 * Message Flow 4 섹션 식별자입니다.
 *
 * <p>FlowCard 의 4 영역 (Identity / Trigger / Action / Collect) 을 코드 레벨에서
 * 구분할 때 사용합니다. 후속 service / validator 단계에서 섹션별 검증 / 직렬화
 * 분기에 활용됩니다.</p>
 */
public enum UiMessageFlowSection {

    /** flowId, workflowName, messageName. */
    IDENTITY,

    /** eventId, transactionId, workflowFilter (SECS only). */
    TRIGGER,

    /** actionName, actionDataIndex. */
    ACTION,

    /** Flow 에 매핑된 DCOP item 목록. */
    COLLECT
}
