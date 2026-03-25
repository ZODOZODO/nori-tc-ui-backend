package com.nori.tc.ui.adapters.redis.registry;

import com.nori.tc.ui.core.service.DeployTraceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Deploy trace 완료 Pub/Sub 채널 수신 리스너입니다.
 */
@Component
public class DeployTracePubSubListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(DeployTracePubSubListener.class);

    private final DeployTraceRegistry deployTraceRegistry;

    public DeployTracePubSubListener(final DeployTraceRegistry deployTraceRegistry) {
        this.deployTraceRegistry = Objects.requireNonNull(deployTraceRegistry,
                "deployTraceRegistry is null");
    }

    @Override
    public void onMessage(final Message message, final byte[] pattern) {
        if (message == null || message.getBody() == null || message.getBody().length == 0) {
            log.warn("Deploy trace Pub/Sub 빈 메시지 수신 - 무시");
            return;
        }

        final String traceId = new String(message.getBody(), StandardCharsets.UTF_8).trim();
        if (traceId.isEmpty()) {
            log.warn("Deploy trace Pub/Sub traceId 공백 메시지 수신 - 무시");
            return;
        }

        deployTraceRegistry.completeFromRedis(traceId);
    }
}
