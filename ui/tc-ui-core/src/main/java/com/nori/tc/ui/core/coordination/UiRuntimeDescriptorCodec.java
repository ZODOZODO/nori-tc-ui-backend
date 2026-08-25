package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiRuntimeDescriptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Objects;

/**
 * {@link UiRuntimeDescriptor} 를 revisioned resource descriptor JSON 으로 직렬화합니다.
 *
 * <p>이 JSON 이 Business({@code BusinessRuntimeDescriptorCodec})와 Gateway
 * ({@code GatewayEquipmentOverlayCodec})의 입력이다. 두 소비자는 모르는 필드를 무시하므로 문서는
 * 하나를 공유한다.</p>
 *
 * <p><b>null 필드는 아예 쓰지 않는다.</b> 소비자 계약에서 "부재" 와 "빈 값" 의 의미가 다르기
 * 때문이다 — {@code null} 을 명시적으로 써 넣으면 소비자가 "빈 snapshot 으로 교체" 로 읽을 수 있다.</p>
 */
public final class UiRuntimeDescriptorCodec {

    private final ObjectMapper objectMapper;

    /**
     * 코덱을 만듭니다.
     *
     * @param objectMapper JSON 직렬화기
     */
    public UiRuntimeDescriptorCodec(final ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
    }

    /**
     * descriptor 를 JSON 으로 직렬화합니다.
     *
     * @param descriptor 직렬화 대상
     * @return descriptor JSON
     */
    public String write(final UiRuntimeDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor is null");
        final ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", UiRuntimeDescriptor.SCHEMA_VERSION);
        root.put("eqpId", descriptor.eqpId());
        root.put("deleted", descriptor.deleted());
        putIfPresent(root, "eqpKey", descriptor.eqpKey());
        putIfPresent(root, "modelVersionKey", descriptor.modelVersionKey());
        if (descriptor.routePartition() != null) {
            root.put("routePartition", descriptor.routePartition());
        }
        putIfPresent(root, "businessJarKey", descriptor.businessJarKey());
        putIfPresent(root, "businessJarFileName", descriptor.businessJarFileName());
        putIfPresent(root, "businessJarSha256", descriptor.businessJarSha256());
        putMapIfPresent(root, "modelParams", descriptor.modelParams());
        putMapIfPresent(root, "eqpParams", descriptor.eqpParams());
        root.put("pluginRuntimeAffected", descriptor.pluginRuntimeAffected());
        putIfPresent(root, "gatewayDesiredState", descriptor.gatewayDesiredState());
        if (descriptor.plcEnabledPollGroups() != null) {
            final ArrayNode groups = root.putArray("plcEnabledPollGroups");
            descriptor.plcEnabledPollGroups().forEach(groups::add);
        }
        return objectMapper.writeValueAsString(root);
    }

    private static void putIfPresent(final ObjectNode root, final String field, final Long value) {
        if (value != null) {
            root.put(field, value);
        }
    }

    private static void putIfPresent(final ObjectNode root, final String field, final String value) {
        if (value != null && !value.isBlank()) {
            root.put(field, value);
        }
    }

    private static void putMapIfPresent(final ObjectNode root, final String field, final Map<String, String> values) {
        if (values == null) {
            return;
        }
        final ObjectNode node = root.putObject(field);
        values.forEach(node::put);
    }
}
