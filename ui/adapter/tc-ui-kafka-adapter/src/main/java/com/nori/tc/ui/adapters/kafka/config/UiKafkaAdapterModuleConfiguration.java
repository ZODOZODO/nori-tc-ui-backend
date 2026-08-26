package com.nori.tc.ui.adapters.kafka.config;

import com.nori.tc.ui.adapters.kafka.assignment.BusinessAppAssignmentKafkaAdapter;
import com.nori.tc.ui.adapters.kafka.publish.UiBusinessEventKafkaPublisher;
import com.nori.tc.ui.adapters.kafka.publish.UiGatewayEventKafkaPublisher;
import com.nori.tc.ui.adapters.kafka.subscribe.UiCommandKafkaSubscriber;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * UI Kafka adapter bean graph를 명시적으로 고정합니다.
 */
@Configuration(proxyBeanMethods = false)
@Import({
        UiKafkaConfiguration.class,
        BusinessAppAssignmentKafkaAdapter.class,
        UiBusinessEventKafkaPublisher.class,
        UiGatewayEventKafkaPublisher.class,
        UiCommandKafkaSubscriber.class,
        UiPublishTimeoutInvariantValidator.class
})
public class UiKafkaAdapterModuleConfiguration {
}
