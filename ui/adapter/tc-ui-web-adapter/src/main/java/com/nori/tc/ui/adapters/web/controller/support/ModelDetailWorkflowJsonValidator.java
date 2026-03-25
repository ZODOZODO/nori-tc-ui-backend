package com.nori.tc.ui.adapters.web.controller.support;

/**
 * workflow/action_data_index 저장 검증을 담당합니다.
 */
final class ModelDetailWorkflowJsonValidator {

    private ModelDetailWorkflowJsonValidator() {
    }

    static void validateWorkflowFilter(final String workflowFilter) {
        ModelDetailWorkflowJsonParser.parseWorkflowFilter(workflowFilter);
    }

    static void validateActionDataIndex(final String actionDataIndex) {
        ModelDetailWorkflowJsonParser.parseActionDataIndex(actionDataIndex);
    }
}
