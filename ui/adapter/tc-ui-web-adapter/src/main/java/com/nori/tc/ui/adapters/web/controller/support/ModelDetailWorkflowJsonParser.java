package com.nori.tc.ui.adapters.web.controller.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * workflow/action_data_index JSON 파싱을 담당합니다.
 */
final class ModelDetailWorkflowJsonParser {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .build();

    private static final Set<String> WORKFLOW_CONDITION_KEYS = Set.of("from", "path", "comparison", "expected", "transforms");
    private static final Set<String> ACTION_DATA_INDEX_ROOT_KEYS = Set.of("mdfTemplateName", "fields");
    private static final Set<String> ACTION_DATA_INDEX_FIELD_KEYS = Set.of("from", "path", "transforms");
    private static final Set<String> WORKFLOW_COMPARISON_KEYS = Set.of(
            "equals",
            "not_equals",
            "greater_than",
            "greater_than_or_equal",
            "less_than",
            "less_than_or_equal",
            "contains",
            "in"
    );

    private ModelDetailWorkflowJsonParser() {
    }

    static WorkflowGroupNode parseWorkflowFilter(final String workflowFilter) {
        final String normalized = ModelDetailWorkflowJsonNormalizer.normalizeOptionalText(workflowFilter);
        if (normalized == null) {
            return null;
        }

        final JsonNode root = readJsonObject(normalized, "workflow_filter");
        return parseWorkflowGroupNode(root, true);
    }

    static ParsedActionDataIndex parseActionDataIndex(final String actionDataIndex) {
        final String normalized = ModelDetailWorkflowJsonNormalizer.normalizeOptionalText(actionDataIndex);
        if (normalized == null) {
            return null;
        }

        final JsonNode root = readJsonObject(normalized, "action_data_index");
        validateAllowedKeys(
                root,
                ACTION_DATA_INDEX_ROOT_KEYS,
                "action_data_index 루트에는 mdfTemplateName, fields만 허용됩니다."
        );

        final JsonNode mdfTemplateNameNode = root.path("mdfTemplateName");
        final String mdfTemplateName = (!mdfTemplateNameNode.isMissingNode() && !mdfTemplateNameNode.isNull())
                ? ModelDetailWorkflowJsonNormalizer.normalizeOptionalText(mdfTemplateNameNode.asString())
                : null;
        final JsonNode fieldsNode = requiredNode(
                root,
                "fields",
                "action_data_index의 fields는 필수입니다."
        );
        if (!fieldsNode.isObject()) {
            throw new IllegalArgumentException("action_data_index의 fields는 JSON object여야 합니다.");
        }

        final Map<String, ActionFieldSpec> fields = new LinkedHashMap<>();
        final Iterator<Map.Entry<String, JsonNode>> iterator = fieldsNode.properties().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<String, JsonNode> entry = iterator.next();
            final String fieldName = ModelDetailWorkflowJsonNormalizer.normalizeOptionalText(entry.getKey());
            if (fieldName == null) {
                throw new IllegalArgumentException("action_data_index의 필드명은 비어 있을 수 없습니다.");
            }
            fields.put(fieldName, parseActionFieldSpec(fieldName, entry.getValue()));
        }
        return new ParsedActionDataIndex(mdfTemplateName, Collections.unmodifiableMap(new LinkedHashMap<>(fields)));
    }

    private static WorkflowGroupNode parseWorkflowGroupNode(final JsonNode node, final boolean root) {
        validateJsonObject(node, "workflow_filter 노드는 JSON object여야 합니다.");

        final Set<String> fieldNames = fieldNames(node);
        final boolean hasAnd = fieldNames.contains("and");
        final boolean hasOr = fieldNames.contains("or");

        if (!hasAnd && !hasOr) {
            if (root) {
                throw new IllegalArgumentException("workflow_filter 루트는 and 또는 or 그룹이어야 합니다.");
            }
            throw new IllegalArgumentException("workflow_filter 그룹 노드는 and 또는 or 중 하나를 가져야 합니다.");
        }
        if (hasAnd && hasOr) {
            throw new IllegalArgumentException("workflow_filter 그룹 노드는 and와 or를 동시에 가질 수 없습니다.");
        }
        if (fieldNames.size() != 1) {
            throw new IllegalArgumentException("workflow_filter 그룹 노드는 and 또는 or 키만 가질 수 있습니다.");
        }

        final String groupKey = hasAnd ? "and" : "or";
        final JsonNode childrenNode = node.path(groupKey);
        if (!childrenNode.isArray()) {
            throw new IllegalArgumentException("workflow_filter의 " + groupKey + " 값은 배열이어야 합니다.");
        }
        if (childrenNode.isEmpty()) {
            throw new IllegalArgumentException("workflow_filter의 " + groupKey + " 그룹은 비어 있을 수 없습니다.");
        }

        final List<WorkflowNode> children = new ArrayList<>();
        for (JsonNode childNode : childrenNode) {
            children.add(parseWorkflowNode(childNode));
        }
        return new WorkflowGroupNode(groupKey, List.copyOf(children));
    }

    private static WorkflowNode parseWorkflowNode(final JsonNode node) {
        validateJsonObject(node, "workflow_filter 노드는 JSON object여야 합니다.");

        final Set<String> fieldNames = fieldNames(node);
        if (fieldNames.contains("and") || fieldNames.contains("or")) {
            return parseWorkflowGroupNode(node, false);
        }
        return parseWorkflowConditionNode(node);
    }

    private static WorkflowConditionNode parseWorkflowConditionNode(final JsonNode node) {
        validateAllowedKeys(
                node,
                WORKFLOW_CONDITION_KEYS,
                "workflow_filter 조건에는 from, path, comparison, expected, transforms만 허용됩니다."
        );

        final String from = parseLookupSource(
                requiredText(node, "from", "workflow_filter 조건의 from은 필수입니다."),
                "workflow_filter 조건의 from은 data 또는 metadata만 허용됩니다."
        );
        final String path = parseRelativePath(
                requiredText(node, "path", "workflow_filter 조건의 path는 필수입니다."),
                "workflow_filter 조건의 path는 from 기준 상대 경로여야 합니다."
        );
        final String comparison = parseWorkflowComparison(
                requiredText(node, "comparison", "workflow_filter 조건의 comparison은 필수입니다.")
        );

        final JsonNode expectedNode = requiredNode(node, "expected", "workflow_filter 조건의 expected는 필수입니다.");
        if (expectedNode.isObject()) {
            throw new IllegalArgumentException("workflow_filter 조건의 expected는 object일 수 없습니다.");
        }

        return new WorkflowConditionNode(
                from,
                path,
                comparison,
                jsonValue(expectedNode),
                parseTransforms(node.path("transforms"), "workflow_filter 조건의 transforms는 배열이어야 합니다.")
        );
    }

    private static ActionFieldSpec parseActionFieldSpec(final String fieldName, final JsonNode fieldNode) {
        if (fieldNode == null || fieldNode.isNull()) {
            throw new IllegalArgumentException("action_data_index 필드 " + fieldName + "는 null일 수 없습니다.");
        }

        if (fieldNode.isString()) {
            return new ActionFieldSpec(
                    "data",
                    parseRelativePath(fieldNode.asString(), "action_data_index 필드 " + fieldName + "의 path는 from 기준 상대 경로여야 합니다."),
                    List.of()
            );
        }

        if (!fieldNode.isObject()) {
            throw new IllegalArgumentException("action_data_index 필드 " + fieldName + "는 문자열 또는 object여야 합니다.");
        }

        validateAllowedKeys(
                fieldNode,
                ACTION_DATA_INDEX_FIELD_KEYS,
                "action_data_index 필드 " + fieldName + "에는 from, path, transforms만 허용됩니다."
        );

        final String from = parseLookupSource(
                requiredText(fieldNode, "from", "action_data_index 필드 " + fieldName + "의 from은 필수입니다."),
                "action_data_index 필드 " + fieldName + "의 from은 data 또는 metadata만 허용됩니다."
        );
        final String path = parseRelativePath(
                requiredText(fieldNode, "path", "action_data_index 필드 " + fieldName + "의 path는 필수입니다."),
                "action_data_index 필드 " + fieldName + "의 path는 from 기준 상대 경로여야 합니다."
        );
        final List<String> transforms = parseTransforms(
                fieldNode.path("transforms"),
                "action_data_index 필드 " + fieldName + "의 transforms는 배열이어야 합니다."
        );
        return new ActionFieldSpec(from, path, transforms);
    }

    private static List<String> parseTransforms(final JsonNode transformsNode, final String invalidMessage) {
        if (transformsNode == null || transformsNode.isNull() || transformsNode.isMissingNode()) {
            return List.of();
        }
        if (!transformsNode.isArray()) {
            throw new IllegalArgumentException(invalidMessage);
        }

        final List<String> transforms = new ArrayList<>();
        for (JsonNode child : transformsNode) {
            transforms.add(parseTransform(child));
        }
        return List.copyOf(transforms);
    }

    private static String parseTransform(final JsonNode transformNode) {
        if (transformNode == null || transformNode.isNull() || transformNode.isMissingNode()) {
            throw new IllegalArgumentException("transform은 null일 수 없습니다.");
        }

        if (transformNode.isString()) {
            final String transformText = ModelDetailWorkflowJsonNormalizer.normalizeOptionalText(transformNode.asString());
            if (transformText == null) {
                throw new IllegalArgumentException("transform 문자열은 비어 있을 수 없습니다.");
            }
            return transformText;
        }

        if (!transformNode.isObject()) {
            throw new IllegalArgumentException("transform은 문자열 또는 object여야 합니다.");
        }

        final String name = ModelDetailWorkflowJsonNormalizer.normalizeOptionalText(
                requiredText(transformNode, "name", "transform의 name은 필수입니다.")
        );
        if (name == null) {
            throw new IllegalArgumentException("transform의 name은 비어 있을 수 없습니다.");
        }

        final JsonNode argsNode = transformNode.path("args");
        if (argsNode.isMissingNode() || argsNode.isNull()) {
            return name.toLowerCase(Locale.ROOT);
        }
        if (!argsNode.isArray()) {
            throw new IllegalArgumentException("transform의 args는 배열이어야 합니다.");
        }

        final List<String> args = new ArrayList<>();
        for (JsonNode argNode : argsNode) {
            args.add(ModelDetailWorkflowJsonSerializer.stringifyPreviewValue(jsonValue(argNode)));
        }
        return name.toLowerCase(Locale.ROOT) + "(" + String.join(", ", args) + ")";
    }

    private static String parseWorkflowComparison(final String rawComparison) {
        final String normalized = ModelDetailWorkflowJsonNormalizer.normalizeOptionalText(rawComparison);
        if (normalized == null) {
            throw new IllegalArgumentException("workflow_filter 조건의 comparison은 필수입니다.");
        }
        final String lowerCase = normalized.toLowerCase(Locale.ROOT);
        if (!WORKFLOW_COMPARISON_KEYS.contains(lowerCase)) {
            throw new IllegalArgumentException("workflow_filter 조건의 comparison은 표준 연산만 허용됩니다.");
        }
        return lowerCase;
    }

    private static String parseLookupSource(final String rawSource, final String invalidMessage) {
        final String normalized = ModelDetailWorkflowJsonNormalizer.normalizeOptionalText(rawSource);
        if (normalized == null) {
            throw new IllegalArgumentException(invalidMessage);
        }
        final String lowerCase = normalized.toLowerCase(Locale.ROOT);
        if (!"data".equals(lowerCase) && !"metadata".equals(lowerCase)) {
            throw new IllegalArgumentException(invalidMessage);
        }
        return lowerCase;
    }

    private static String parseRelativePath(final String rawPath, final String invalidMessage) {
        final String normalized = ModelDetailWorkflowJsonNormalizer.normalizeOptionalText(rawPath);
        if (normalized == null) {
            throw new IllegalArgumentException(invalidMessage);
        }
        if (normalized.startsWith("data.") || normalized.startsWith("metadata.")) {
            throw new IllegalArgumentException(invalidMessage);
        }
        return normalized;
    }

    private static JsonNode readJsonObject(final String rawJson, final String label) {
        try {
            final JsonNode root = OBJECT_MAPPER.readTree(rawJson);
            validateJsonObject(root, label + "는 JSON object여야 합니다.");
            return root;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException(label + " JSON 형식이 올바르지 않습니다.", exception);
        }
    }

    private static void validateAllowedKeys(
            final JsonNode node,
            final Set<String> allowedKeys,
            final String baseMessage
    ) {
        final Set<String> invalidKeys = new LinkedHashSet<>();
        for (String fieldName : node.propertyNames()) {
            if (!allowedKeys.contains(fieldName)) {
                invalidKeys.add(fieldName);
            }
        }

        if (!invalidKeys.isEmpty()) {
            throw new IllegalArgumentException(baseMessage + " 허용되지 않은 키: " + String.join(", ", invalidKeys));
        }
    }

    private static String requiredText(final JsonNode node, final String fieldName, final String requiredMessage) {
        final JsonNode child = requiredNode(node, fieldName, requiredMessage);
        final String text = child.isString() ? child.asString() : child.asString(null);
        final String normalized = ModelDetailWorkflowJsonNormalizer.normalizeOptionalText(text);
        if (normalized == null) {
            throw new IllegalArgumentException(requiredMessage);
        }
        return normalized;
    }

    private static JsonNode requiredNode(final JsonNode node, final String fieldName, final String requiredMessage) {
        final JsonNode child = node.path(fieldName);
        if (child.isMissingNode() || child.isNull()) {
            throw new IllegalArgumentException(requiredMessage);
        }
        return child;
    }

    private static void validateJsonObject(final JsonNode node, final String message) {
        if (node == null || node.isNull() || !node.isObject()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static Set<String> fieldNames(final JsonNode node) {
        final Set<String> target = new LinkedHashSet<>();
        target.addAll(node.propertyNames());
        return target;
    }

    static Object jsonValue(final JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isString()) {
            return node.asString();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isArray()) {
            final List<Object> values = new ArrayList<>();
            for (JsonNode child : node) {
                values.add(jsonValue(child));
            }
            return List.copyOf(values);
        }
        if (node.isObject()) {
            final Map<String, Object> values = new LinkedHashMap<>();
            final Iterator<Map.Entry<String, JsonNode>> iterator = node.properties().iterator();
            while (iterator.hasNext()) {
                final Map.Entry<String, JsonNode> entry = iterator.next();
                values.put(entry.getKey(), jsonValue(entry.getValue()));
            }
            return Map.copyOf(values);
        }
        return node.asString();
    }

    sealed interface WorkflowNode permits WorkflowGroupNode, WorkflowConditionNode {
    }

    record WorkflowGroupNode(
            String groupType,
            List<WorkflowNode> children
    ) implements WorkflowNode {
    }

    record WorkflowConditionNode(
            String from,
            String path,
            String comparison,
            Object expected,
            List<String> transforms
    ) implements WorkflowNode {
    }

    record ParsedActionDataIndex(
            String mdfTemplateName,
            Map<String, ActionFieldSpec> fields
    ) {
    }

    record ActionFieldSpec(
            String from,
            String path,
            List<String> transforms
    ) {
    }
}
