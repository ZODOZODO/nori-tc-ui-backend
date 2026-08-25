package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 원장에 고정된 canonical manifest 를 실행 가능한 뷰로 해석합니다(§13.1/§13.4).
 */
public final class UiOperationManifestReader {

    private final ObjectMapper objectMapper;

    /**
     * 리더를 만듭니다.
     *
     * @param objectMapper JSON 파서
     */
    public UiOperationManifestReader(final ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
    }

    /**
     * manifest JSON 을 해석합니다.
     *
     * @param manifestJson canonical manifest JSON
     * @return 실행 뷰
     */
    public UiOperationManifestView read(final String manifestJson) {
        final JsonNode root = objectMapper.readTree(Objects.requireNonNull(manifestJson, "manifestJson is null"));
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("manifest JSON must be an object");
        }
        final int schemaVersion = root.path("schemaVersion").asInt(UiOperationManifestFactory.SCHEMA_VERSION);
        if (schemaVersion != UiOperationManifestFactory.SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported manifest schemaVersion: " + schemaVersion);
        }

        final List<String> resourceKeys = new ArrayList<>();
        final JsonNode resourceKeysNode = root.get("resourceKeys");
        if (resourceKeysNode != null && resourceKeysNode.isArray()) {
            resourceKeysNode.forEach(node -> resourceKeys.add(node.asString()));
        }

        final Map<String, String> descriptors = readJsonMap(root, "descriptors");
        final Map<String, String> previousDescriptors = readJsonMap(root, "previousDescriptors");

        final Map<String, Long> baseRevisions = new LinkedHashMap<>();
        final JsonNode baseRevisionsNode = root.get("expectedBaseRevisions");
        if (baseRevisionsNode != null && baseRevisionsNode.isObject()) {
            for (final Map.Entry<String, JsonNode> entry : baseRevisionsNode.properties()) {
                baseRevisions.put(entry.getKey(), entry.getValue().asLong());
            }
        }

        return new UiOperationManifestView(
                textOrNull(root, "eventType"),
                textOrNull(root, "eqpId"),
                textOrNull(root, "actor"),
                textOrNull(root, "executionId"),
                textOrNull(root, "interfaceType"),
                root.path("requiresGatewayParticipant").asBoolean(false),
                resourceKeys,
                descriptors,
                baseRevisions,
                root.has("catalogRevision") ? root.get("catalogRevision").asLong() : null,
                textOrNull(root, "catalogDigest"),
                previousDescriptors,
                textOrNull(root, "dbCommandKind"),
                root.get("dbCommand") == null || root.get("dbCommand").isNull()
                        ? null
                        : objectMapper.writeValueAsString(root.get("dbCommand")));
    }

    private Map<String, String> readJsonMap(final JsonNode root, final String field) {
        final Map<String, String> values = new LinkedHashMap<>();
        final JsonNode node = root.get(field);
        if (node != null && node.isObject()) {
            for (final Map.Entry<String, JsonNode> entry : node.properties()) {
                values.put(entry.getKey(), objectMapper.writeValueAsString(entry.getValue()));
            }
        }
        return values;
    }

    private static String textOrNull(final JsonNode root, final String field) {
        final JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        final String value = node.asString().trim();
        return value.isEmpty() ? null : value;
    }
}
