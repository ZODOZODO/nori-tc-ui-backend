package com.nori.tc.ui.adapters.web.controller.support;

import com.nori.tc.db.domain.model.TcModelWorkflow;

import java.util.ArrayList;
import java.util.List;

/**
 * Model 상세 화면의 workflow/filter/data index 축약 표시 문자열을 생성합니다.
 *
 * <p>UI가 raw JSON/XML을 그대로 table cell에 노출하지 않아도 되도록
 * 새 canonical 계약 기준 preview 문자열을 제공합니다.</p>
 */
public final class ModelDetailPreviewSupport {

    private ModelDetailPreviewSupport() {
    }

    /**
     * workflow row의 preview 셀 값을 생성합니다.
     *
     * @param workflow workflow 원본 row
     * @return 컬럼 순서에 맞는 preview 값 목록
     */
    public static List<String> buildWorkflowPreviewValues(final TcModelWorkflow workflow) {
        final List<String> previewValues = new ArrayList<>(List.of(
                nullToEmpty(workflow.workflowName()),
                nullToEmpty(workflow.messageName()),
                nullToEmpty(workflow.eventId()),
                nullToEmpty(workflow.transactionId()),
                summarizeWorkflowFilter(workflow.workflowFilter()),
                nullToEmpty(workflow.actionName()),
                summarizeActionDataIndex(workflow.actionDataIndex())
        ));
        return List.copyOf(previewValues);
    }

    /**
     * workflow_filter를 새 canonical 계약 기준 preview 문자열로 생성합니다.
     */
    public static String summarizeWorkflowFilter(final String workflowFilter) {
        return ModelDetailWorkflowJsonSupport.buildWorkflowFilterPreview(workflowFilter);
    }

    /**
     * action_data_index를 새 canonical 계약 기준 preview 문자열로 생성합니다.
     */
    public static String summarizeActionDataIndex(final String actionDataIndex) {
        return ModelDetailWorkflowJsonSupport.buildActionDataIndexPreview(actionDataIndex);
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }
}
