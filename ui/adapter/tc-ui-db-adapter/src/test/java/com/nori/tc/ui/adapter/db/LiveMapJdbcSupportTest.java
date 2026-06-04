package com.nori.tc.ui.adapter.db;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * JSONB ↔ JsonNode 변환 무손실 검증(R3 — resultMap 부재 JDBC 의 직렬화 경로 회귀 차단).
 */
class LiveMapJdbcSupportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void jsonNodeRoundtripIsLossless() {
        final LiveMapJdbcSupport support = new LiveMapJdbcSupport(MAPPER);
        final ObjectNode node = MAPPER.createObjectNode();
        node.put("v", 1);
        final ObjectNode root = node.putObject("root");
        root.put("className", "Group");
        root.putArray("children")
                .addObject()
                .put("className", "Rect")
                .putObject("attrs")
                .put("x", 0)
                .put("y", 0)
                .put("width", 80);

        final String json = support.toJson(node);
        final JsonNode parsed = support.parseJson(json);

        assertEquals(node, parsed);
    }

    @Test
    void nullJsonNodeMapsToSqlNull() {
        final LiveMapJdbcSupport support = new LiveMapJdbcSupport(MAPPER);

        assertNull(support.toJson(null));
        assertNull(support.parseJson(null));
    }
}
