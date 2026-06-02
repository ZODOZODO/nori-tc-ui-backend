package com.nori.tc.ui.core.port.db;

import com.nori.tc.ui.domain.model.UiMessageFlow;

/**
 * Message Flow 저장 포트입니다.
 *
 * <p>FlowCard 기반 Model 상세 화면의 Flow 생성 / 수정 계약을 정의합니다.</p>
 * <ul>
 *   <li>{@code POST /api/model/{modelVersionKey}/flows}</li>
 *   <li>{@code PUT  /api/model/{modelVersionKey}/flows/{flowId}}</li>
 * </ul>
 *
 * <p>Collect 섹션 (DCOP item) 저장은 기존
 * {@link ModelDetailCommandPort#saveDetailRows(long, String, java.util.List)}
 * (detailNode = {@code dcop-itemes}) 를 재사용하므로 이 포트에서 다루지 않습니다.</p>
 */
public interface ModelFlowCommandPort {

    /**
     * Identity 섹션만 채운 신규 Flow row 1 개를 등록합니다.
     *
     * <p>POST 시 body 는 {@code (workflowName, messageName)} 만 받으며 Trigger / Action /
     * Collect 섹션은 후속 PUT 호출로 채웁니다.</p>
     *
     * @param modelVersionKey 대상 모델 버전 키
     * @param workflowName    Workflow 명 (blank 금지 — 호출 측 service 검증)
     * @param messageName     Message 명 (blank 금지 — 호출 측 service 검증)
     * @return 생성된 Message Flow PK (= flowId)
     */
    long insertFlow(long modelVersionKey, String workflowName, String messageName);

    /**
     * 단일 Flow 의 Identity + Trigger + Action 섹션을 통합 UPDATE 합니다.
     *
     * <p>대상은 {@code tc_model_message_flow} row 1 개. Collect 섹션 (DCOP item) 은 본 메서드 범위
     * 밖이며, 호출 측에서 기존 {@code PUT /details/dcop-itemes} 흐름으로 별도 처리합니다.</p>
     *
     * <p>메서드명은 Plan 의 {@code updateFlowIdentitySection} 대신 {@code updateFlow} 로 단순화 —
     * 형제 port 명명 컨벤션 ({@code updateLastSeenAt}, {@code updateDeployStatus}) 과 일치하며,
     * 실제 갱신 범위가 Identity + Trigger + Action 3 섹션이므로 단일 섹션을 시사하는
     * {@code IdentitySection} 접미사를 제거하는 편이 의미상 더 정확합니다. 정확한 갱신 범위는
     * 본 javadoc 으로 명확화합니다.</p>
     *
     * @param modelVersionKey 대상 모델 버전 키
     * @param flowId          Message Flow PK (= tc_model_message_flow.message_flow_key)
     * @param request         갱신할 Flow aggregate (dcopItems 필드는 무시)
     */
    void updateFlow(long modelVersionKey, long flowId, UiMessageFlow request);

    /**
     * 단일 Flow row 1 개를 삭제합니다.
     *
     * <p>{@code tc_model_message_flow_dcop_item} join table 의 해당 Flow binding 도 함께 정리한 뒤
     * {@code tc_model_message_flow} row 를 삭제합니다 (FK 안전 순서).</p>
     *
     * @param modelVersionKey 대상 모델 버전 키 (소유권 검증용)
     * @param flowId          Message Flow PK (= tc_model_message_flow.message_flow_key)
     */
    void deleteFlow(long modelVersionKey, long flowId);
}
