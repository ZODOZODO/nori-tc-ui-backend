package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.port.db.ModelFlowQueryPort;
import com.nori.tc.ui.domain.model.UiMessageFlow;
import com.nori.tc.ui.domain.model.UiMessageFlowSummary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Message Flow 조회 서비스입니다.
 *
 * <p>FlowCard 기반 Model 상세 화면 (좌측 트리 + 4 섹션 detail) 의 read-side aggregator 입니다.
 * Trigger 영역 (eventId / transactionId / workflowFilter) 의 Socket vs SECS 분기는
 * port adapter 가 record 채움으로 표현하고, 서비스는 record 를 그대로 전달합니다.</p>
 */
@Service
public class ModelFlowQueryService {

    private final ModelFlowQueryPort modelFlowQueryPort;

    public ModelFlowQueryService(
            final ModelFlowQueryPort modelFlowQueryPort
    ) {
        this.modelFlowQueryPort = Objects.requireNonNull(modelFlowQueryPort, "modelFlowQueryPort is null");
    }

    /**
     * model_version_key 기준 Flow 요약 목록을 조회합니다.
     *
     * <p>정렬 / 트리 그룹화 책임은 각각 port (message_flow_key ASC) 와 호출 측 FE 에 위임 — 서비스는
     * 변형 없이 그대로 전달합니다.</p>
     *
     * @param modelVersionKey 대상 모델 버전 키
     * @return 평탄(flat) Flow 요약 목록
     */
    public List<UiMessageFlowSummary> findFlowSummaries(final long modelVersionKey) {
        return modelFlowQueryPort.findFlowSummaries(modelVersionKey);
    }

    /**
     * model_version_key + flowId 기준 Flow 상세 aggregate 를 조회합니다.
     *
     * @param modelVersionKey 대상 모델 버전 키
     * @param flowId          Message Flow PK (= tc_model_message_flow.message_flow_key)
     * @return 4 섹션 aggregate
     * @throws UiNotFoundException flowId 가 해당 model_version 에 존재하지 않을 때
     */
    public UiMessageFlow findFlowDetail(final long modelVersionKey, final long flowId) {
        return modelFlowQueryPort.findFlowByMessageFlowKey(modelVersionKey, flowId)
                .orElseThrow(() -> new UiNotFoundException(
                        "Flow를 찾을 수 없습니다. modelVersionKey=" + modelVersionKey + ", flowId=" + flowId
                ));
    }
}
