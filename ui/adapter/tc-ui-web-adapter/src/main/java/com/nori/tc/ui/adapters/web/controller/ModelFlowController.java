package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.controller.support.ModelDetailWorkflowJsonSupport;
import com.nori.tc.ui.adapters.web.dto.request.ModelFlowCreateRequest;
import com.nori.tc.ui.adapters.web.dto.request.ModelFlowUpdateRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelFlowCreatedResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelFlowDetailResponse;
import com.nori.tc.ui.adapters.web.dto.response.ModelFlowListResponse;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.service.ModelFlowCommandService;
import com.nori.tc.ui.core.service.ModelFlowQueryService;
import com.nori.tc.ui.domain.model.UiMessageFlow;
import com.nori.tc.ui.domain.model.UiMessageFlowSummary;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Message Flow REST endpoint 입니다.
 *
 * <p>POST/GET list/GET detail/PUT 4 메서드로 Identity + Trigger + Action + Collect 의
 * 4 섹션 aggregate 를 다룹니다. Collect 섹션 (DCOP item) 의 CRUD 는 기존
 * {@code PUT /api/model/{modelVersionKey}/details/dcop-itemes} 흐름을 그대로 사용합니다.</p>
 *
 * <p>책임 분리:</p>
 * <ul>
 *   <li>controller — JSON 스키마 검증 ({@code workflow_filter} / {@code action_data_index})
 *     후 service 위임. 응답은 {@link ApiResponse} 로 통일.</li>
 *   <li>service — blank / EDIT-version / path-body mismatch 등 비즈니스 검증.</li>
 *   <li>db adapter — unique constraint 위반 → {@code UiConflictException} 변환.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/model/{modelVersionKey}/flows")
public class ModelFlowController {

    private final ModelFlowQueryService queryService;
    private final ModelFlowCommandService commandService;

    public ModelFlowController(
            final ModelFlowQueryService queryService,
            final ModelFlowCommandService commandService
    ) {
        this.queryService = Objects.requireNonNull(queryService, "queryService is null");
        this.commandService = Objects.requireNonNull(commandService, "commandService is null");
    }

    /**
     * Identity 섹션만 채운 신규 Flow row 1 개를 생성합니다.
     *
     * @param modelVersionKey 대상 모델 버전 키
     * @param request 신규 Flow 의 workflowName / messageName
     * @return 생성된 flowId 응답
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ModelFlowCreatedResponse>> createFlow(
            @PathVariable final long modelVersionKey,
            @RequestBody final ModelFlowCreateRequest request
    ) {
        final long flowId = commandService.createFlow(
                modelVersionKey,
                request == null ? null : request.workflowName(),
                request == null ? null : request.messageName()
        );
        return ResponseEntity.ok(ApiResponse.success(new ModelFlowCreatedResponse(flowId)));
    }

    /**
     * model_version_key 기준 Flow 요약 목록을 조회합니다.
     *
     * @param modelVersionKey 대상 모델 버전 키
     * @return Flow 요약 목록 응답
     */
    @GetMapping
    public ResponseEntity<ApiResponse<ModelFlowListResponse>> listFlows(
            @PathVariable final long modelVersionKey
    ) {
        final List<UiMessageFlowSummary> summaries = queryService.findFlowSummaries(modelVersionKey);
        return ResponseEntity.ok(ApiResponse.success(ModelFlowListResponse.from(summaries)));
    }

    /**
     * 단일 Flow 의 4 섹션 상세를 조회합니다.
     *
     * @param modelVersionKey 대상 모델 버전 키
     * @param flowId Workflow PK
     * @return Flow 상세 응답
     */
    @GetMapping("/{flowId}")
    public ResponseEntity<ApiResponse<ModelFlowDetailResponse>> getFlow(
            @PathVariable final long modelVersionKey,
            @PathVariable final long flowId
    ) {
        final UiMessageFlow flow = queryService.findFlowDetail(modelVersionKey, flowId);
        return ResponseEntity.ok(ApiResponse.success(ModelFlowDetailResponse.from(flow)));
    }

    /**
     * 단일 Flow 의 Identity + Trigger + Action 섹션을 통합 갱신합니다.
     *
     * <p>{@code workflowFilter} 와 {@code actionDataIndex} 는 controller 에서 JSON 스키마
     * 검증을 먼저 수행하고, 통과 후 service 에 위임합니다. JSON 검증 실패는
     * {@link UiBadRequestException} 으로 변환되어 400 으로 응답됩니다.</p>
     *
     * <p>응답은 갱신 직후 detail 을 재조회하여 반환합니다 — FE 가 PUT 한 번으로
     * 최신 4 섹션 상태를 확보할 수 있도록 합니다.</p>
     *
     * @param modelVersionKey 대상 모델 버전 키
     * @param flowId Workflow PK
     * @param request 갱신할 Flow 내용
     * @return 갱신 후 Flow 상세 응답
     */
    @PutMapping("/{flowId}")
    public ResponseEntity<ApiResponse<ModelFlowDetailResponse>> updateFlow(
            @PathVariable final long modelVersionKey,
            @PathVariable final long flowId,
            @RequestBody final ModelFlowUpdateRequest request
    ) {
        if (request == null) {
            throw new UiBadRequestException("Flow 요청 본문이 비어 있습니다.");
        }
        validateWorkflowFilterJson(request.workflowFilter());
        validateActionDataIndexJson(request.actionDataIndex());

        commandService.updateFlow(modelVersionKey, flowId, request.toDomain(flowId));

        final UiMessageFlow updated = queryService.findFlowDetail(modelVersionKey, flowId);
        return ResponseEntity.ok(ApiResponse.success(ModelFlowDetailResponse.from(updated)));
    }

    private static void validateWorkflowFilterJson(final String workflowFilter) {
        if (workflowFilter == null) {
            return;
        }
        try {
            ModelDetailWorkflowJsonSupport.validateWorkflowFilter(workflowFilter);
        } catch (IllegalArgumentException exception) {
            throw new UiBadRequestException(
                    "workflow_filter가 올바르지 않습니다. " + exception.getMessage()
            );
        }
    }

    private static void validateActionDataIndexJson(final String actionDataIndex) {
        if (actionDataIndex == null) {
            return;
        }
        try {
            ModelDetailWorkflowJsonSupport.validateActionDataIndex(actionDataIndex);
        } catch (IllegalArgumentException exception) {
            throw new UiBadRequestException(
                    "action_data_index가 올바르지 않습니다. " + exception.getMessage()
            );
        }
    }
}
