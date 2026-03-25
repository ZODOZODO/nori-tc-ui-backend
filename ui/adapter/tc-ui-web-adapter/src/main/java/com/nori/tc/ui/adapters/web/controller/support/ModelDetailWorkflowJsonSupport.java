package com.nori.tc.ui.adapters.web.controller.support;

/**
 * Model workflow 상세 화면에서 사용하는 JSON 계약 facade 입니다.
 */
public final class ModelDetailWorkflowJsonSupport {

    private ModelDetailWorkflowJsonSupport() {
    }

    public static String buildWorkflowFilterPreview(final String workflowFilter) {
        final String fallback = ModelDetailWorkflowJsonNormalizer.normalizeFirstLine(workflowFilter);
        if (fallback.isEmpty()) {
            return "";
        }

        try {
            final ModelDetailWorkflowJsonParser.WorkflowGroupNode root =
                    ModelDetailWorkflowJsonParser.parseWorkflowFilter(workflowFilter);
            return root == null ? "" : ModelDetailWorkflowJsonSerializer.summarizeWorkflowNode(root);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    public static String buildActionDataIndexPreview(final String actionDataIndex) {
        final String fallback = ModelDetailWorkflowJsonNormalizer.normalizeFirstLine(actionDataIndex);
        if (fallback.isEmpty()) {
            return "";
        }

        try {
            final ModelDetailWorkflowJsonParser.ParsedActionDataIndex parsed =
                    ModelDetailWorkflowJsonParser.parseActionDataIndex(actionDataIndex);
            if (parsed == null) {
                return "";
            }
            return ModelDetailWorkflowJsonSerializer.summarizeActionDataIndex(parsed);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    public static void validateWorkflowFilter(final String workflowFilter) {
        ModelDetailWorkflowJsonValidator.validateWorkflowFilter(workflowFilter);
    }

    public static void validateActionDataIndex(final String actionDataIndex) {
        ModelDetailWorkflowJsonValidator.validateActionDataIndex(actionDataIndex);
    }
}
