package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.nori.tc.ui.core.port.db.ModelDetailQueryPort;
import com.nori.tc.ui.domain.model.UiModelParam;

import java.util.Objects;

/**
 * model parameter payload를 runtime 메시지 규격으로 조립합니다.
 *
 * <p>런타임 경로에서는 business가 tc_model_param을 다시 조회하지 않도록
 * backend가 항상 payload(items)를 채워 내려보내는 정본 조립기입니다.</p>
 */
final class ModelParamPayloadSupport {

    private final ModelDetailQueryPort modelDetailQueryPort;
    private final ObjectMapper objectMapper;

    ModelParamPayloadSupport(
            final ModelDetailQueryPort modelDetailQueryPort,
            final ObjectMapper objectMapper
    ) {
        this.modelDetailQueryPort = Objects.requireNonNull(modelDetailQueryPort, "modelDetailQueryPort is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
    }

    ObjectNode ensureModelParamsPayload(
            final JsonNode existingNode,
            final long modelVersionKey
    ) {
        final ObjectNode root = existingNode != null && existingNode.isObject()
                ? ((ObjectNode) existingNode.deepCopy())
                : objectMapper.createObjectNode();
        final ArrayNode items = objectMapper.createArrayNode();
        if (modelVersionKey > 0L) {
            for (UiModelParam param : modelDetailQueryPort.findParamsByModelVersionKey(modelVersionKey)) {
                if (param == null || param.paramName() == null || param.paramName().isBlank()) {
                    continue;
                }
                final ObjectNode item = items.addObject();
                item.put("name", param.paramName());
                item.put("value", param.paramValue() == null ? "" : param.paramValue());
            }
        }
        root.set("items", items);
        return root;
    }
}
