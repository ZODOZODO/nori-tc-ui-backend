package com.nori.tc.ui.adapter.db;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

/**
 * live-map JDBC 어댑터의 JSONB ↔ JsonNode 변환 보조입니다(DeployJdbcStoreSupport 답습).
 *
 * <p>DB 경계는 String + {@code CAST(:x AS JSONB)}, 서버 표현은 {@link JsonNode}(Jackson3) 입니다.
 * canvas_json 처럼 NULL 허용 컬럼은 {@code null} JsonNode 를 SQL NULL 로 전달하기 위해
 * {@link #toJson(JsonNode)} 가 {@code null} 을 반환합니다.</p>
 */
final class LiveMapJdbcSupport {

    private final ObjectMapper objectMapper;

    LiveMapJdbcSupport(final ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
    }

    String toJson(final JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("JSON 직렬화에 실패했습니다.", exception);
        }
    }

    JsonNode parseJson(final String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException("JSON 역직렬화에 실패했습니다.", exception);
        }
    }
}
