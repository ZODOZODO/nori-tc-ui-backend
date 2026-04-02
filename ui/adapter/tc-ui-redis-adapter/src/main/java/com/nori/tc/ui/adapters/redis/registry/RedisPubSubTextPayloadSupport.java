package com.nori.tc.ui.adapters.redis.registry;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Redis Pub/Sub 메시지 본문을 문자열 식별자로 안전하게 복원하는 지원 유틸입니다.
 *
 * <p>현재 UI backend는 {@link RedisSerializer#json()} 기반 템플릿으로 문자열 traceId를 발행하므로,
 * Pub/Sub 수신 시 원문 바이트를 바로 UTF-8 문자열로 해석하면 {@code "traceId"} 형태의 JSON 따옴표가
 * 그대로 남을 수 있습니다. 이 유틸은 동일 serializer로 먼저 역직렬화하고, 필요 시 평문 fallback을 적용해
 * 로컬 registry key와 동일한 문자열을 복원합니다.</p>
 */
final class RedisPubSubTextPayloadSupport {

    private RedisPubSubTextPayloadSupport() {
    }

    static String readTextPayload(
            final Message message,
            final RedisSerializer<Object> valueSerializer
    ) {
        Objects.requireNonNull(message, "message is null");
        Objects.requireNonNull(valueSerializer, "valueSerializer is null");

        final byte[] body = message.getBody();
        if (body == null || body.length == 0) {
            return "";
        }

        final Object deserialized = tryDeserialize(body, valueSerializer);
        if (deserialized != null) {
            return normalize(deserialized.toString());
        }

        return normalize(new String(body, StandardCharsets.UTF_8));
    }

    private static Object tryDeserialize(
            final byte[] body,
            final RedisSerializer<Object> valueSerializer
    ) {
        try {
            return valueSerializer.deserialize(body);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String normalize(final String rawText) {
        final String trimmed = rawText == null ? "" : rawText.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }
}
