package com.nori.tc.ui.adapters.web.controller.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * Model workflow 상세 화면에서 사용하는 JSON 계약 검증/preview 공통 지원 클래스입니다.
 *
 * <p>역할:</p>
 * <ul>
 *   <li>{@code workflow_filter}를 새 canonical 계약 기준으로 검증합니다.</li>
 *   <li>{@code action_data_index}를 새 canonical 계약 기준으로 검증합니다.</li>
 *   <li>검증을 통과한 JSON을 preview 한 줄 문자열로 축약합니다.</li>
 * </ul>
 *
 * <p>주의:</p>
 * <ul>
 *   <li>저장 검증은 새 계약만 허용합니다.</li>
 *   <li>preview는 기존 데이터 호환을 위해 파싱 실패 시 첫 줄 fallback을 허용합니다.</li>
 * </ul>
 */
public final class ModelDetailWorkflowJsonSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

    private ModelDetailWorkflowJsonSupport() {
    }

    /**
     * {@code workflow_filter}를 새 계약 기준 preview 문자열로 변환합니다.
     *
     * <p>저장 전 검증과 달리 preview는 기존 데이터 호환을 위해 파싱 실패 시
     * 첫 번째 의미 있는 줄을 그대로 반환합니다.</p>
     *
     * @param workflowFilter 원본 workflow_filter 문자열
     * @return preview 한 줄 문자열
     */
    public static String buildWorkflowFilterPreview(final String workflowFilter) {
        final String fallback = normalizeFirstLine(workflowFilter);
        if (fallback.isEmpty()) {
            return "";
        }

        try {
            final WorkflowGroupNode root = parseWorkflowFilter(workflowFilter);
            return root == null ? "" : summarizeWorkflowNode(root);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    /**
     * {@code action_data_index}를 새 계약 기준 preview 문자열로 변환합니다.
     *
     * <p>저장 전 검증과 달리 preview는 기존 데이터 호환을 위해 파싱 실패 시
     * 첫 번째 의미 있는 줄을 그대로 반환합니다.</p>
     *
     * @param actionDataIndex 원본 action_data_index 문자열
     * @return preview 한 줄 문자열
     */
    public static String buildActionDataIndexPreview(final String actionDataIndex) {
        final String fallback = normalizeFirstLine(actionDataIndex);
        if (fallback.isEmpty()) {
            return "";
        }

        try {
            final ParsedActionDataIndex parsed = parseActionDataIndex(actionDataIndex);
            if (parsed == null) {
                return "";
            }
            return summarizeActionDataIndex(parsed);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    /**
     * 저장 전 {@code workflow_filter}의 구조를 검증합니다.
     *
     * @param workflowFilter 원본 workflow_filter 문자열
     * @throws IllegalArgumentException 새 계약을 위반하면 발생
     */
    public static void validateWorkflowFilter(final String workflowFilter) {
        parseWorkflowFilter(workflowFilter);
    }

    /**
     * 저장 전 {@code action_data_index}의 구조를 검증합니다.
     *
     * @param actionDataIndex 원본 action_data_index 문자열
     * @throws IllegalArgumentException 새 계약을 위반하면 발생
     */
    public static void validateActionDataIndex(final String actionDataIndex) {
        parseActionDataIndex(actionDataIndex);
    }

    /**
     * workflow_filter 원문을 검증 완료된 AST로 파싱합니다.
     */
    private static WorkflowGroupNode parseWorkflowFilter(final String workflowFilter) {
        final String normalized = normalizeOptionalText(workflowFilter);
        if (normalized == null) {
            return null;
        }

        final JsonNode root = readJsonObject(normalized, "workflow_filter");
        return parseWorkflowGroupNode(root, true);
    }

    /**
     * action_data_index 원문을 검증 완료된 구조로 파싱합니다.
     */
    private static ParsedActionDataIndex parseActionDataIndex(final String actionDataIndex) {
        final String normalized = normalizeOptionalText(actionDataIndex);
        if (normalized == null) {
            return null;
        }

        final JsonNode root = readJsonObject(normalized, "action_data_index");
        validateAllowedKeys(
                root,
                ACTION_DATA_INDEX_ROOT_KEYS,
                "action_data_index 루트에는 mdfTemplateName, fields만 허용됩니다."
        );

        // mdfTemplateName은 필수 값입니다. 없거나 공백이면 예외를 발생시킵니다.
        // 런타임(BusinessActionDataIndexHybridResolver)에서도 동일하게 필수로 검증하므로,
        // 저장 시점에 미리 거부하여 런타임 오류를 예방합니다.
        final JsonNode mdfTemplateNameNode = root.path("mdfTemplateName");
        final String mdfTemplateName = (!mdfTemplateNameNode.isMissingNode() && !mdfTemplateNameNode.isNull())
                ? normalizeOptionalText(mdfTemplateNameNode.asText())
                : null;
        if (mdfTemplateName == null) {
            throw new IllegalArgumentException("action_data_index의 mdfTemplateName은 필수입니다.");
        }
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
            final String fieldName = normalizeOptionalText(entry.getKey());
            if (fieldName == null) {
                throw new IllegalArgumentException("action_data_index의 필드명은 비어 있을 수 없습니다.");
            }
            fields.put(fieldName, parseActionFieldSpec(fieldName, entry.getValue()));
        }
        return new ParsedActionDataIndex(mdfTemplateName, Collections.unmodifiableMap(new LinkedHashMap<>(fields)));
    }

    /**
     * workflow의 and/or 그룹 노드를 파싱합니다.
     */
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

    /**
     * workflow의 단일 노드를 group/condition 중 하나로 파싱합니다.
     */
    private static WorkflowNode parseWorkflowNode(final JsonNode node) {
        validateJsonObject(node, "workflow_filter 노드는 JSON object여야 합니다.");

        final Set<String> fieldNames = fieldNames(node);
        if (fieldNames.contains("and") || fieldNames.contains("or")) {
            return parseWorkflowGroupNode(node, false);
        }
        return parseWorkflowConditionNode(node);
    }

    /**
     * workflow의 조건 노드를 파싱합니다.
     */
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

    /**
     * action_data_index 단일 필드 spec을 파싱합니다.
     */
    private static ActionFieldSpec parseActionFieldSpec(final String fieldName, final JsonNode fieldNode) {
        if (fieldNode == null || fieldNode.isNull()) {
            throw new IllegalArgumentException("action_data_index 필드 " + fieldName + "는 null일 수 없습니다.");
        }

        if (fieldNode.isTextual()) {
            return new ActionFieldSpec(
                    "data",
                    parseRelativePath(fieldNode.asText(), "action_data_index 필드 " + fieldName + "의 path는 from 기준 상대 경로여야 합니다."),
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

    /**
     * transform 배열을 preview 가능한 문자열 체인으로 파싱합니다.
     */
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

    /**
     * transform 하나를 compact preview 문자열로 변환합니다.
     */
    private static String parseTransform(final JsonNode transformNode) {
        if (transformNode == null || transformNode.isNull() || transformNode.isMissingNode()) {
            throw new IllegalArgumentException("transform은 null일 수 없습니다.");
        }

        if (transformNode.isTextual()) {
            final String transformText = normalizeOptionalText(transformNode.asText());
            if (transformText == null) {
                throw new IllegalArgumentException("transform 문자열은 비어 있을 수 없습니다.");
            }
            return transformText;
        }

        if (!transformNode.isObject()) {
            throw new IllegalArgumentException("transform은 문자열 또는 object여야 합니다.");
        }

        final String name = normalizeOptionalText(requiredText(transformNode, "name", "transform의 name은 필수입니다."));
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
            args.add(stringifyPreviewValue(jsonValue(argNode)));
        }
        return name.toLowerCase(Locale.ROOT) + "(" + String.join(", ", args) + ")";
    }

    /**
     * workflow condition의 comparison 키를 표준 집합으로 검증합니다.
     */
    private static String parseWorkflowComparison(final String rawComparison) {
        final String normalized = normalizeOptionalText(rawComparison);
        if (normalized == null) {
            throw new IllegalArgumentException("workflow_filter 조건의 comparison은 필수입니다.");
        }
        final String lowerCase = normalized.toLowerCase(Locale.ROOT);
        if (!WORKFLOW_COMPARISON_KEYS.contains(lowerCase)) {
            throw new IllegalArgumentException("workflow_filter 조건의 comparison은 표준 연산만 허용됩니다.");
        }
        return lowerCase;
    }

    /**
     * data/metadata lookup source를 검증합니다.
     */
    private static String parseLookupSource(final String rawSource, final String invalidMessage) {
        final String normalized = normalizeOptionalText(rawSource);
        if (normalized == null) {
            throw new IllegalArgumentException(invalidMessage);
        }
        final String lowerCase = normalized.toLowerCase(Locale.ROOT);
        if (!"data".equals(lowerCase) && !"metadata".equals(lowerCase)) {
            throw new IllegalArgumentException(invalidMessage);
        }
        return lowerCase;
    }

    /**
     * from 기준 상대 경로만 허용하도록 path를 검증합니다.
     */
    private static String parseRelativePath(final String rawPath, final String invalidMessage) {
        final String normalized = normalizeOptionalText(rawPath);
        if (normalized == null) {
            throw new IllegalArgumentException(invalidMessage);
        }
        if (normalized.startsWith("data.") || normalized.startsWith("metadata.")) {
            throw new IllegalArgumentException(invalidMessage);
        }
        return normalized;
    }

    /**
     * workflow AST를 한 줄 preview 문자열로 변환합니다.
     */
    private static String summarizeWorkflowNode(final WorkflowNode node) {
        if (node instanceof WorkflowGroupNode groupNode) {
            final List<String> childSummaries = groupNode.children().stream()
                    .map(ModelDetailWorkflowJsonSupport::summarizeWorkflowNode)
                    .toList();
            return groupNode.groupType() + "(" + String.join(", ", childSummaries) + ")";
        }

        final WorkflowConditionNode conditionNode = (WorkflowConditionNode) node;
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

    /**
     * action_data_index 파싱 결과를 한 줄 preview 문자열로 변환합니다.
     */
    private static String summarizeActionDataIndex(final ParsedActionDataIndex parsed) {
        if (parsed.fields().isEmpty()) {
            return "mdfTemplateName=" + parsed.mdfTemplateName() + " / fields=0";
        }

        final Map.Entry<String, ActionFieldSpec> firstField = parsed.fields().entrySet().iterator().next();
        final StringBuilder summary = new StringBuilder();
        summary.append("mdfTemplateName=").append(parsed.mdfTemplateName());
        summary.append(" / ").append(firstField.getKey()).append(" {from=").append(firstField.getValue().from());
        summary.append(", path=").append(firstField.getValue().path());
        if (!firstField.getValue().transforms().isEmpty()) {
            summary.append(", transforms=[").append(String.join(", ", firstField.getValue().transforms())).append(']');
        }
        summary.append('}');
        return summary.toString();
    }

    /**
     * JSON object 여부를 검증합니다.
     */
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

    /**
     * 허용되지 않은 키가 섞여 있는지 검증합니다.
     */
    private static void validateAllowedKeys(
            final JsonNode node,
            final Set<String> allowedKeys,
            final String baseMessage
    ) {
        final Set<String> invalidKeys = new LinkedHashSet<>();
        final Iterator<String> fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            final String fieldName = fieldNames.next();
            if (!allowedKeys.contains(fieldName)) {
                invalidKeys.add(fieldName);
            }
        }

        if (!invalidKeys.isEmpty()) {
            throw new IllegalArgumentException(baseMessage + " 허용되지 않은 키: " + String.join(", ", invalidKeys));
        }
    }

    /**
     * 필수 텍스트 필드를 읽습니다.
     */
    private static String requiredText(final JsonNode node, final String fieldName, final String requiredMessage) {
        final JsonNode child = requiredNode(node, fieldName, requiredMessage);
        final String text = child.isTextual() ? child.asText() : child.asText(null);
        final String normalized = normalizeOptionalText(text);
        if (normalized == null) {
            throw new IllegalArgumentException(requiredMessage);
        }
        return normalized;
    }

    /**
     * 필수 JSON 노드를 읽습니다.
     */
    private static JsonNode requiredNode(final JsonNode node, final String fieldName, final String requiredMessage) {
        final JsonNode child = node.path(fieldName);
        if (child.isMissingNode() || child.isNull()) {
            throw new IllegalArgumentException(requiredMessage);
        }
        return child;
    }

    /**
     * object 노드 여부를 검증합니다.
     */
    private static void validateJsonObject(final JsonNode node, final String message) {
        if (node == null || node.isNull() || !node.isObject()) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * JSON object의 필드명 집합을 순서 보존 형태로 읽습니다.
     */
    private static Set<String> fieldNames(final JsonNode node) {
        final Set<String> target = new LinkedHashSet<>();
        final Iterator<String> iterator = node.fieldNames();
        while (iterator.hasNext()) {
            target.add(iterator.next());
        }
        return target;
    }

    /**
     * preview/검증에서 공통 사용하는 JSON 값을 Java 값으로 변환합니다.
     */
    private static Object jsonValue(final JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
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
        return node.asText();
    }

    /**
     * preview 표시에 맞는 문자열로 값을 직렬화합니다.
     */
    private static String stringifyPreviewValue(final Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String stringValue) {
            return "\"" + stringValue + "\"";
        }
        if (value instanceof List<?> listValue) {
            final List<String> children = listValue.stream()
                    .map(ModelDetailWorkflowJsonSupport::stringifyPreviewValue)
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

    /**
     * 비어 있는 입력을 null로 정규화합니다.
     */
    private static String normalizeOptionalText(final String value) {
        if (value == null) {
            return null;
        }
        final String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * preview fallback용 첫 번째 의미 있는 줄을 반환합니다.
     */
    private static String normalizeFirstLine(final String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        final String[] lines = value.split("\\R");
        for (String line : lines) {
            final String normalized = collapseWhitespace(line);
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }
        return collapseWhitespace(value);
    }

    /**
     * preview fallback 시 공백을 한 칸으로 접습니다.
     */
    private static String collapseWhitespace(final String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    /**
     * workflow_filter의 group/condition 다형 노드를 표현합니다.
     */
    private sealed interface WorkflowNode permits WorkflowGroupNode, WorkflowConditionNode {
    }

    /**
     * workflow_filter and/or 그룹 노드입니다.
     */
    private record WorkflowGroupNode(
            String groupType,
            List<WorkflowNode> children
    ) implements WorkflowNode {
    }

    /**
     * workflow_filter 조건 노드입니다.
     */
    private record WorkflowConditionNode(
            String from,
            String path,
            String comparison,
            Object expected,
            List<String> transforms
    ) implements WorkflowNode {
    }

    /**
     * action_data_index 전체 파싱 결과입니다.
     */
    private record ParsedActionDataIndex(
            String mdfTemplateName,
            Map<String, ActionFieldSpec> fields
    ) {
    }

    /**
     * action_data_index 단일 필드 정의입니다.
     */
    private record ActionFieldSpec(
            String from,
            String path,
            List<String> transforms
    ) {
    }
}
