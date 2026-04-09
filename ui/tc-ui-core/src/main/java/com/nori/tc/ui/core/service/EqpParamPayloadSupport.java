package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.domain.eqp.UiEqpParam;

import java.util.Objects;

/**
 * eqp parameter payload를 runtime 메시지 규격으로 조립합니다.
 *
 * <p>설계상 eqp param은 EQP_PARAMETER_UPDATE에서만 반영되므로,
 * backend는 이 helper를 통해 version/items를 완전한 payload로 내려보내야 합니다.</p>
 */
final class EqpParamPayloadSupport {

    private final EqpQueryPort eqpQueryPort;
    private final ObjectMapper objectMapper;

    EqpParamPayloadSupport(
            final EqpQueryPort eqpQueryPort,
            final ObjectMapper objectMapper
    ) {
        this.eqpQueryPort = Objects.requireNonNull(eqpQueryPort, "eqpQueryPort is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
    }

    ObjectNode ensureEqpParamsPayload(
            final JsonNode existingNode,
            final String eqpId,
            final String version
    ) {
        final ObjectNode root = existingNode != null && existingNode.isObject()
                ? ((ObjectNode) existingNode.deepCopy())
                : objectMapper.createObjectNode();
        if (version != null && !version.isBlank()) {
            root.put("version", version.trim());
        }

        final ArrayNode items = objectMapper.createArrayNode();
        if (eqpId != null && !eqpId.isBlank() && version != null && !version.isBlank()) {
            for (UiEqpParam param : eqpQueryPort.findParamsByEqpIdAndVersion(eqpId, version.trim())) {
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
