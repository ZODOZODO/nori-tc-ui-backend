package com.nori.tc.ui.core.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Canonical observability logs for ui-backend runtime flows.
 *
 * <p>BusinessObservationLogger / GatewayObservationLogger 와 동일한 lifecycle anchor 문법
 * ({@code <rcvd ...>} / {@code <send ...>})을 사용하여, 운영자가 {@code <rcvd}/{@code <send}
 * grep 으로 gateway -> business -> ui-backend 흐름을 단일 기준으로 추적할 수 있게 한다.</p>
 */
public final class UiObservationLogger {

    private static final Logger log = LoggerFactory.getLogger(UiObservationLogger.class);
    private static final String UNKNOWN = "N/A";

    private UiObservationLogger() {
        // utility class
    }

    /**
     * tc.ui.commands 응답 수신 milestone anchor 로그(INFO)를 남깁니다.
     */
    public static void logCommandReplyReceived(
            final String eventType,
            final String topic,
            final int partition,
            final long offset,
            final String key,
            final String eqpId,
            final String traceId,
            final String source,
            final String status
    ) {
        log.info(
                "<rcvd UI : {}> outcome=RECEIVED, topic={}, partition={}, offset={}, key={}, eqpId={}, traceId={}, source={}, status={}",
                text(eventType),
                text(topic),
                partition,
                offset,
                text(key),
                text(eqpId),
                text(traceId),
                text(source),
                text(status)
        );
    }

    /**
     * UI 이벤트 발행 시도 milestone anchor 로그(INFO)를 남깁니다.
     *
     * @param target 발행 대상 토큰(GATEWAY / BUSINESS)
     */
    public static void logEventSendRequested(
            final String target,
            final String eventType,
            final String topic,
            final String key,
            final String eqpId,
            final String traceId,
            final String source
    ) {
        log.info(
                "<send {} : {}> outcome=REQUESTED, topic={}, key={}, eqpId={}, traceId={}, source={}",
                token(target),
                text(eventType),
                text(topic),
                text(key),
                text(eqpId),
                text(traceId),
                text(source)
        );
    }

    private static String token(final String value) {
        if (value == null || value.isBlank()) {
            return "EVENT";
        }
        final String upper = value.trim().toUpperCase();
        final StringBuilder sanitized = new StringBuilder(upper.length());
        for (int i = 0; i < upper.length(); i++) {
            final char ch = upper.charAt(i);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')) {
                sanitized.append(ch);
                continue;
            }
            sanitized.append('_');
        }
        return sanitized.isEmpty() ? "EVENT" : sanitized.toString();
    }

    private static String text(final String value) {
        if (value == null) {
            return UNKNOWN;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? UNKNOWN : trimmed;
    }
}
