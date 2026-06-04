package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.port.db.ModelCrudPort;
import com.nori.tc.ui.core.port.db.ModelFlowCommandPort;
import com.nori.tc.ui.domain.model.UiMessageFlow;
import com.nori.tc.ui.domain.model.UiModel;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Message Flow 생성 / 수정 서비스입니다.
 *
 * <p>POST {@code /api/model/{modelVersionKey}/flows} 와
 * PUT {@code /api/model/{modelVersionKey}/flows/{flowId}} 의 비즈니스 검증을 담당합니다.
 * Identity + Trigger + Action 통합 PUT 만 다루며, Collect (DCOP item) 저장은 기존
 * {@code PUT /details/dcop-itemes} 흐름에서 처리합니다.</p>
 *
 * <p>책임 분리:</p>
 * <ul>
 *   <li>본 service — blank / null / EDIT-version / flowId mismatch 등 비즈니스 검증.</li>
 *   <li>web adapter (controller) — {@code workflow_filter} / {@code action_data_index} 의
 *     JSON 스키마 검증 (기존 {@code ModelDetailWorkflowJsonSupport} 재사용). 본 service 는
 *     web adapter 를 의존하지 않으므로 JSON 검증 책임을 갖지 않는다.</li>
 *   <li>db adapter — (workflow_name, message_name) 등 unique constraint 위반은
 *     {@code DbDuplicateKeyException} → {@code UiConflictException} 으로 변환되어 노출.
 *     사전 조회 검증은 race condition 만 늘릴 뿐 제거 못 하므로 수행하지 않는다.</li>
 * </ul>
 *
 * <p>EDIT 버전 가드는 {@code model.modelVersion()} 가 문자열 "EDIT" 인지 비교 —
 * 형제 {@code ModelDetailController#saveDetailRows} 와 동일 규칙. {@code UiModelStatus} 가
 * 아닌 modelVersion 컬럼이 편집 가능 여부의 단일 진실원이다.</p>
 */
@Service
public class ModelFlowCommandService {

    private static final String EDIT_MODEL_VERSION = "EDIT";

    private final ModelCrudPort modelCrudPort;
    private final ModelFlowCommandPort modelFlowCommandPort;

    public ModelFlowCommandService(
            final ModelCrudPort modelCrudPort,
            final ModelFlowCommandPort modelFlowCommandPort
    ) {
        this.modelCrudPort = Objects.requireNonNull(modelCrudPort, "modelCrudPort is null");
        this.modelFlowCommandPort = Objects.requireNonNull(modelFlowCommandPort, "modelFlowCommandPort is null");
    }

    /**
     * Identity 섹션만 채운 신규 Flow row 1 개를 생성합니다.
     *
     * @param modelVersionKey 대상 모델 버전 키
     * @param workflowName    Workflow 명 (blank 금지)
     * @param messageName     Message 명 (blank 금지)
     * @return 생성된 Message Flow PK (= flowId)
     * @throws UiBadRequestException workflowName / messageName blank 또는 EDIT 가 아닌 모델 버전
     * @throws UiNotFoundException modelVersionKey 에 해당하는 모델이 없을 때
     */
    public long createFlow(final long modelVersionKey, final String workflowName, final String messageName) {
        requireNonBlank(workflowName, "workflowName은 비어 있을 수 없습니다.");
        requireNonBlank(messageName, "messageName은 비어 있을 수 없습니다.");
        requireEditVersionModel(modelVersionKey);
        return modelFlowCommandPort.insertFlow(modelVersionKey, workflowName, messageName);
    }

    /**
     * 단일 Flow 의 Identity + Trigger + Action 섹션을 통합 갱신합니다.
     *
     * <p>path 의 flowId 와 body 의 {@code request.flowId()} 는 반드시 같아야 한다 — 다르면
     * 클라이언트의 라우팅 실수이므로 즉시 400 으로 거부한다.</p>
     *
     * @param modelVersionKey 대상 모델 버전 키
     * @param flowId          Message Flow PK (= tc_model_message_flow.message_flow_key)
     * @param request         갱신할 Flow aggregate
     * @throws UiBadRequestException 검증 실패 (null body / flowId mismatch / blank name / non-EDIT 모델)
     * @throws UiNotFoundException modelVersionKey 에 해당하는 모델이 없을 때
     */
    public void updateFlow(final long modelVersionKey, final long flowId, final UiMessageFlow request) {
        if (request == null) {
            throw new UiBadRequestException("Flow 요청 본문이 비어 있습니다.");
        }
        if (request.flowId() != flowId) {
            throw new UiBadRequestException(
                    "path flowId 와 body flowId 가 일치하지 않습니다. pathFlowId=" + flowId
                            + ", bodyFlowId=" + request.flowId()
            );
        }
        requireNonBlank(request.workflowName(), "workflowName은 비어 있을 수 없습니다.");
        requireNonBlank(request.messageName(), "messageName은 비어 있을 수 없습니다.");
        requireNonBlank(request.actionName(), "actionName은 비어 있을 수 없습니다.");
        requireEditVersionModel(modelVersionKey);
        modelFlowCommandPort.updateFlow(modelVersionKey, flowId, request);
    }

    /**
     * 단일 Flow 를 삭제합니다 (Collect 바인딩 포함).
     *
     * @param modelVersionKey 대상 모델 버전 키
     * @param flowId          Message Flow PK (= tc_model_message_flow.message_flow_key)
     * @throws UiBadRequestException EDIT 가 아닌 모델 버전
     * @throws UiNotFoundException modelVersionKey 에 해당하는 모델이 없을 때
     */
    public void deleteFlow(final long modelVersionKey, final long flowId) {
        requireEditVersionModel(modelVersionKey);
        modelFlowCommandPort.deleteFlow(modelVersionKey, flowId);
    }

    private void requireEditVersionModel(final long modelVersionKey) {
        final UiModel model = modelCrudPort.findByModelVersionKey(modelVersionKey)
                .orElseThrow(() -> new UiNotFoundException(
                        "모델을 찾을 수 없습니다. modelVersionKey=" + modelVersionKey
                ));
        if (!EDIT_MODEL_VERSION.equalsIgnoreCase(model.modelVersion())) {
            throw new UiBadRequestException(
                    "EDIT version만 변경할 수 있습니다. modelVersionKey=" + modelVersionKey
                            + ", modelVersion=" + model.modelVersion()
            );
        }
    }

    private static void requireNonBlank(final String value, final String message) {
        if (value == null || value.isBlank()) {
            throw new UiBadRequestException(message);
        }
    }
}
