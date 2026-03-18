package com.nori.tc.ui.adapters.kafka.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * UI Backend Kafka 설정 클래스입니다.
 *
 * <p>역할:</p>
 * <ul>
 *   <li>{@link UiKafkaTopicProperties}, {@link UiKafkaPublishProperties} ConfigurationProperties 활성화</li>
 *   <li>{@code tc.ui.commands} 전용 KafkaListenerContainerFactory 생성
 *       (MANUAL_IMMEDIATE ACK 모드, String 역직렬화)</li>
 * </ul>
 *
 * <p>왜 별도 ContainerFactory를 사용하는가:</p>
 * <p>Spring Boot 기본 {@code kafkaListenerContainerFactory}의 ACK 모드는
 * {@code spring.kafka.listener.ack-mode} 설정에 의존하므로,
 * UI commands 구독자만 MANUAL_IMMEDIATE를 보장하기 위해 전용 factory를 정의합니다.
 * String 역직렬화를 명시하여 Gateway/Business의 JSON 응답 메시지를
 * 안전하게 수신합니다.</p>
 *
 * <p>DLT 관련 구성은 D03 정책에 따라 제거되었습니다.
 * 파싱 실패 처리는 {@code UiCommandKafkaSubscriber}에서
 * WARN + 메트릭 + ACK로 직접 종결합니다.</p>
 */
@Configuration
@EnableConfigurationProperties({UiKafkaTopicProperties.class, UiKafkaPublishProperties.class})
public class UiKafkaConfiguration {

    private static final Logger log = LoggerFactory.getLogger(UiKafkaConfiguration.class);

    /**
     * tc.ui.commands 전용 Kafka Listener Container Factory입니다.
     *
     * <p>설정 항목:</p>
     * <ul>
     *   <li>ACK 모드: MANUAL_IMMEDIATE - 처리 완료 후 명시적으로 offset 커밋</li>
     *   <li>Key 역직렬화: StringDeserializer</li>
     *   <li>Value 역직렬화: StringDeserializer (JSON 문자열 -> ObjectMapper 후처리)</li>
     *   <li>auto.commit: false (MANUAL_IMMEDIATE와 함께 명시 비활성화)</li>
     * </ul>
     *
     * @param bootstrapServers Kafka 브로커 주소 (spring.kafka.bootstrap-servers)
     * @return MANUAL_IMMEDIATE ACK 모드의 ConcurrentKafkaListenerContainerFactory
     */
    @Bean("uiCommandListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> uiCommandListenerContainerFactory(
            @Value("${spring.kafka.bootstrap-servers}") final String bootstrapServers
    ) {
        final Map<String, Object> consumerProps = buildCommandsConsumerProperties(bootstrapServers);
        final DefaultKafkaConsumerFactory<String, String> consumerFactory =
                new DefaultKafkaConsumerFactory<>(consumerProps);

        final ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // MANUAL_IMMEDIATE: onMessage 내부 acknowledge() 호출 시 즉시 커밋
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        log.info(
                "uiCommandListenerContainerFactory 초기화 완료. bootstrapServers={}, ackMode=MANUAL_IMMEDIATE",
                bootstrapServers
        );
        return factory;
    }

    /**
     * tc.ui.commands 구독자 전용 Kafka Consumer 프로퍼티 맵을 생성합니다.
     *
     * <p>Key/Value 역직렬화를 StringDeserializer로 명시하여
     * Gateway/Business Core가 발행하는 JSON 응답 문자열을 안전하게 수신합니다.
     * 최종 역직렬화/검증/오류정책은 UiCommandKafkaSubscriber에서 수행합니다.</p>
     *
     * @param bootstrapServers Kafka 브로커 주소
     * @return Consumer 설정 프로퍼티 맵
     */
    private static Map<String, Object> buildCommandsConsumerProperties(final String bootstrapServers) {
        final Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // MANUAL_IMMEDIATE와 함께 auto.commit을 명시적으로 비활성화합니다.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return props;
    }
}
