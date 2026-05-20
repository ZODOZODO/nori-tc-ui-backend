package com.nori.tc.ui.adapters.web.dto.response;

/**
 * Message Flow 신규 생성 응답 DTO 입니다.
 *
 * <p>POST {@code /api/model/{modelVersionKey}/flows} 의 응답 본문입니다.
 * FE 는 응답 받은 {@code flowId} 를 그대로 좌측 트리 selection 에 사용합니다.</p>
 *
 * @param flowId 생성된 Workflow PK (= tc_model_workflow.workflow_key)
 */
public record ModelFlowCreatedResponse(
        long flowId
) {
}
