package com.nori.tc.ui.adapters.redis.registry;

import com.nori.tc.ui.core.port.redis.DeployTraceRedisPort;
import com.nori.tc.ui.core.service.DeployTraceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

class DeployTracePubSubListenerTest {

    private CapturingDeployTraceRegistry deployTraceRegistry;

    private DeployTracePubSubListener listener;

    @BeforeEach
    void setUp() {
        deployTraceRegistry = new CapturingDeployTraceRegistry();
        listener = new DeployTracePubSubListener(deployTraceRegistry, RedisSerializer.json());
    }

    @Test
    @DisplayName("JSON 직렬화된 deploy traceId 메시지는 따옴표 없이 registry로 전달한다")
    void jsonSerializedTraceId_isForwardedWithoutQuotes() {
        final Message message = messageWithBody(RedisSerializer.json().serialize("DEPLOY-trace-json-001"));

        listener.onMessage(message, null);

        assertCompletedTraceIds("DEPLOY-trace-json-001");
    }

    @Test
    @DisplayName("평문 deploy traceId 메시지도 그대로 registry로 전달한다")
    void plainTraceId_isForwardedAsIs() {
        final Message message = messageWithBody("DEPLOY-trace-plain-001".getBytes(StandardCharsets.UTF_8));

        listener.onMessage(message, null);

        assertCompletedTraceIds("DEPLOY-trace-plain-001");
    }

    @Test
    @DisplayName("공백 payload는 무시한다")
    void blankPayload_isIgnored() {
        final Message message = messageWithBody("   ".getBytes(StandardCharsets.UTF_8));

        listener.onMessage(message, null);

        assertCompletedTraceIds();
    }

    private Message messageWithBody(final byte[] body) {
        return new DefaultMessage("tc:ui:backend:deploy-trace:complete".getBytes(StandardCharsets.UTF_8), body);
    }

    private void assertCompletedTraceIds(final String... expectedTraceIds) {
        org.junit.jupiter.api.Assertions.assertIterableEquals(
                List.of(expectedTraceIds),
                deployTraceRegistry.completedTraceIds()
        );
    }

    private static final class CapturingDeployTraceRegistry extends DeployTraceRegistry {

        private final List<String> completedTraceIds = new ArrayList<>();

        private CapturingDeployTraceRegistry() {
            super(DeployTraceRedisPort.noop());
        }

        @Override
        public void completeFromRedis(final String traceId) {
            completedTraceIds.add(traceId);
        }

        private List<String> completedTraceIds() {
            return List.copyOf(completedTraceIds);
        }
    }
}
