package com.nori.tc.ui.core.coordination;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

/**
 * descriptor JSON 에서 DB 확정 판정에 쓰는 필드를 읽습니다(§13.7).
 *
 * <p>UI Backend 는 descriptor 를 <b>생산</b>하지만, 확정 시점에는 원장에 고정된 문서를 다시 읽어야
 * 합니다 — 인계받은 executor 가 로컬 요청 객체를 갖고 있지 않기 때문입니다.</p>
 */
public final class UiRuntimeDescriptorReader {

    /**
     * DB 확정 판정에 쓰는 descriptor 필드입니다.
     *
     * @param eqpId 대상 설비
     * @param deleted 삭제 tombstone 여부
     * @param modelVersionKey 목표 modelVersionKey(부재면 null)
     * @param routePartition 목표 route partition(부재면 null)
     * @param businessJarKey 목표 Business JAR key(부재면 null)
     */
    public record DescriptorFields(
            String eqpId,
            boolean deleted,
            Long modelVersionKey,
            Integer routePartition,
            Long businessJarKey
    ) {
    }

    private final ObjectMapper objectMapper;

    /**
     * 리더를 만듭니다.
     *
     * @param objectMapper JSON 파서
     */
    public UiRuntimeDescriptorReader(final ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
    }

    /**
     * descriptor JSON 을 읽습니다.
     *
     * @param descriptorJson descriptor JSON
     * @return 판정 필드
     */
    public DescriptorFields read(final String descriptorJson) {
        final JsonNode root = objectMapper.readTree(Objects.requireNonNull(descriptorJson, "descriptorJson is null"));
        return new DescriptorFields(
                textOrNull(root, "eqpId"),
                root.path("deleted").asBoolean(false),
                longOrNull(root, "modelVersionKey"),
                integerOrNull(root, "routePartition"),
                longOrNull(root, "businessJarKey"));
    }

    private static String textOrNull(final JsonNode root, final String field) {
        final JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asString();
    }

    private static Long longOrNull(final JsonNode root, final String field) {
        final JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asLong();
    }

    private static Integer integerOrNull(final JsonNode root, final String field) {
        final JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asInt();
    }
}
