package com.nori.tc.ui.core.port.db;

import com.nori.tc.ui.domain.model.UiMessageFlow;
import com.nori.tc.ui.domain.model.UiMessageFlowSummary;

import java.util.List;
import java.util.Optional;

/**
 * Message Flow 조회 포트입니다.
 *
 * <p>FlowCard 기반 Model 상세 화면에서 사용하는 Flow 목록 / 상세 조회 계약을 정의합니다.</p>
 * <ul>
 *   <li>{@code GET /api/model/{modelVersionKey}/flows}</li>
 *   <li>{@code GET /api/model/{modelVersionKey}/flows/{flowId}}</li>
 * </ul>
 */
public interface ModelFlowQueryPort {

    /**
     * model_version_key 기준 Flow 요약 목록을 조회합니다.
     *
     * <p>정렬은 message_flow_key 오름차순 — 신규 생성된 Flow 가 목록 끝에 오도록 합니다.
     * 트리 그룹화 (workflowName 별 묶기) 는 호출 측 FE 에서 수행합니다.</p>
     *
     * @param modelVersionKey 대상 모델 버전 키
     * @return 평탄(flat) Flow 요약 목록
     */
    List<UiMessageFlowSummary> findFlowSummaries(long modelVersionKey);

    /**
     * model_version_key + flowId 기준 Flow 상세 aggregate 를 조회합니다.
     *
     * @param modelVersionKey 대상 모델 버전 키
     * @param flowId          Message Flow PK (= tc_model_message_flow.message_flow_key)
     * @return 4 섹션 aggregate, 미존재 시 빈 Optional
     */
    Optional<UiMessageFlow> findFlowByMessageFlowKey(long modelVersionKey, long flowId);
}
