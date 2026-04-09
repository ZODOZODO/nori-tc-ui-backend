package com.nori.tc.ui.adapters.web.controller.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * workflow/action_data_index preview 직렬화를 담당합니다.
 */
final class ModelDetailWorkflowJsonSerializer {

    private ModelDetailWorkflowJsonSerializer() {
    }

    static String summarizeWorkflowNode(final ModelDetailWorkflowJsonParser.WorkflowNode node) {
        if (node instanceof ModelDetailWorkflowJsonParser.WorkflowGroupNode groupNode) {
            final List<String> childSummaries = groupNode.children().stream()
                    .map(ModelDetailWorkflowJsonSerializer::summarizeWorkflowNode)
                    .toList();
            return groupNode.groupType() + "(" + String.join(", ", childSummaries) + ")";
        }

        final ModelDetailWorkflowJsonParser.WorkflowConditionNode conditionNode =
                (ModelDetailWorkflowJsonParser.WorkflowConditionNode) node;
        final StringBuilder summary = new StringBuilder();
        summary.append(conditionNode.from()).append('.').append(conditionNode.path());
        summary.append(" {comparison=").append(conditionNode.comparison());
        summary.append(", expected=").append(stringifyPreviewValue(conditionNode.expected()));
        if (!conditionNode.transforms().isEmpty()) {
            summary.append(", transforms=[").append(String.join(", ", conditionNode.transforms())).append(']');
        }
        summary.append('}');
        return summary.toString();
    }

    static String summarizeActionDataIndex(final ModelDetailWorkflowJsonParser.ParsedActionDataIndex parsed) {
        final String normalizedTemplateName = parsed.mdfTemplateName();
        final String prefix = normalizedTemplateName == null || normalizedTemplateName.isBlank()
                ? ""
                : "mdfTemplateName=" + normalizedTemplateName + " / ";

        if (parsed.fields().isEmpty()) {
            return prefix.isEmpty() ? "fields=0" : prefix + "fields=0";
        }

        final Map.Entry<String, ModelDetailWorkflowJsonParser.ActionFieldSpec> firstField =
                parsed.fields().entrySet().iterator().next();
        final StringBuilder summary = new StringBuilder();
        summary.append(prefix);
        summary.append(firstField.getKey()).append(" {from=").append(firstField.getValue().from());
        summary.append(", path=").append(firstField.getValue().path());
        if (!firstField.getValue().transforms().isEmpty()) {
            summary.append(", transforms=[").append(String.join(", ", firstField.getValue().transforms())).append(']');
        }
        summary.append('}');
        return summary.toString();
    }

    static String stringifyPreviewValue(final Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String stringValue) {
            return "\"" + stringValue + "\"";
        }
        if (value instanceof List<?> listValue) {
            final List<String> children = listValue.stream()
                    .map(ModelDetailWorkflowJsonSerializer::stringifyPreviewValue)
                    .toList();
            return "[" + String.join(", ", children) + "]";
        }
        if (value instanceof Map<?, ?> mapValue) {
            final List<String> entries = new ArrayList<>();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                entries.add(String.valueOf(entry.getKey()) + "=" + stringifyPreviewValue(entry.getValue()));
            }
            return "{" + String.join(", ", entries) + "}";
        }
        return String.valueOf(value);
    }
}
