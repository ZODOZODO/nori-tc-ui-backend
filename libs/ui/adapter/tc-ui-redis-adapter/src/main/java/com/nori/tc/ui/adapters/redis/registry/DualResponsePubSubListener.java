package com.nori.tc.ui.adapters.redis.registry;

import com.nori.tc.ui.core.registry.DualResponseRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * DualResponse 완료 Pub/Sub 채널 수신 리스너입니다.
 *
 * <p>동작:</p>
 * <ol>
 *   <li>Redis 채널에서 traceId 문자열을 수신합니다.</li>
 *   <li>{@link DualResponseRegistry#completeFromRedis(String)}를 호출합니다.</li>
 *   <li>해당 인스턴스에 대기 중인 Future가 있으면 즉시 완료됩니다.</li>
 * </ol>
 */
@Component
public class DualResponsePubSubListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(DualResponsePubSubListener.class);

    private final DualResponseRegistry dualResponseRegistry;

    public DualResponsePubSubListener(final DualResponseRegistry dualResponseRegistry) {
        this.dualResponseRegistry = Objects.requireNonNull(dualResponseRegistry,
                "dualResponseRegistry is null");
    }

    /**
     * Redis Pub/Sub 메시지를 수신합니다.
     *
     * @param message 수신 메시지
     * @param pattern 구독 패턴 (ChannelTopic 사용 시 null 가능)
     */
    @Override
    public void onMessage(final Message message, final byte[] pattern) {
        if (message == null || message.getBody() == null || message.getBody().length == 0) {
            log.warn("DualResponse Pub/Sub 빈 메시지 수신 - 무시");
            return;
        }

        final String traceId = new String(message.getBody(), StandardCharsets.UTF_8).trim();
        if (traceId.isEmpty()) {
            log.warn("DualResponse Pub/Sub traceId 공백 메시지 수신 - 무시");
            return;
        }

        if (log.isTraceEnabled()) {
            log.trace("DualResponse Pub/Sub 완료 신호 수신. traceId={}", traceId);
        }
        dualResponseRegistry.completeFromRedis(traceId);
    }
}
