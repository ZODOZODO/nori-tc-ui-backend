package com.nori.tc.ui.adapters.kafka.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nori.tc.ui.adapters.kafka.config.UiKafkaPublishProperties;
import com.nori.tc.ui.adapters.kafka.config.UiKafkaTopicProperties;
import com.nori.tc.ui.adapters.kafka.exception.UiKafkaPublishException;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.model.UiCommandMessage;
import com.nori.tc.ui.core.port.messaging.UiGatewayEqpRoutePartitionLookupPort;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UiGatewayEventKafkaPublisher} 단위 테스트입니다.
 */
@ExtendWith(MockitoExtension.class)
class UiGatewayEventKafkaPublisherTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock
    private UiGatewayEqpRoutePartitionLookupPort routePartitionLookupPort;

    @Test
    @DisplayName("max.request.size 초과 메시지는 발행 전에 차단")
    void oversizedMessage_blockBeforeSend() {
        final UiKafkaTopicProperties topicProperties = new UiKafkaTopicProperties();
        topicProperties.setGatewayEventsTopic("tc.ui.events.gateway");
        topicProperties.setBusinessEventsTopic("tc.ui.events.business");
        topicProperties.setCommandsTopic("tc.ui.commands");
        topicProperties.validate();

        final UiKafkaPublishProperties publishProperties = new UiKafkaPublishProperties();
        publishProperties.setPublishTimeoutSeconds(1L);
        publishProperties.setMaxRequestBytes(120);
        publishProperties.validate();

        final UiGatewayEventKafkaPublisher publisher = new UiGatewayEventKafkaPublisher(
                kafkaTemplate,
                topicProperties,
                publishProperties,
                routePartitionLookupPort,
                new ObjectMapper()
        );

        when(routePartitionLookupPort.findRoutePartition("EQP-OVERSIZE")).thenReturn(Optional.of(0));

        final UiCommandMessage oversized = new UiCommandMessage(
                UiCommandEventType.EQP_CREATE,
                "trace-oversize",
                "TC-UI-BACKEND",
                "EQP-OVERSIZE",
                "SECS",
                "X".repeat(1024),
                null
        );

        assertThrows(UiKafkaPublishException.class, () -> publisher.publish(oversized));
        verify(kafkaTemplate, never()).send(org.mockito.ArgumentMatchers.<ProducerRecord<String, Object>>any());
    }
}
