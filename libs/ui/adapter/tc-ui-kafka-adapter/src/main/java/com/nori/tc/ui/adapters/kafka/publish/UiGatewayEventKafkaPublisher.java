package com.nori.tc.ui.adapters.kafka.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nori.tc.messaging.kafka.contract.KafkaHeaderSupport;
import com.nori.tc.messaging.kafka.contract.KafkaUiTaskEventType;
import com.nori.tc.messaging.kafka.contract.KafkaUiTaskMessage;
import com.nori.tc.ui.adapters.kafka.config.UiKafkaPublishProperties;
import com.nori.tc.ui.adapters.kafka.config.UiKafkaTopicProperties;
import com.nori.tc.ui.adapters.kafka.exception.UiKafkaPublishException;
import com.nori.tc.ui.core.model.UiCommandMessage;
import com.nori.tc.ui.core.port.messaging.UiGatewayEqpRoutePartitionLookupPort;
import com.nori.tc.ui.core.port.messaging.UiGatewayEventPublishPort;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code tc.ui.events.gateway} 토픽으로 UI 이벤트를 발행하는 Kafka 어댑터입니다.
 */
@Component
public class UiGatewayEventKafkaPublisher implements UiGatewayEventPublishPort {

    private static final Logger log = LoggerFactory.getLogger(UiGatewayEventKafkaPublisher.class);
    private static final String REASON_NO_ROUTE_PARTITION = "ROUTE_PARTITION_NOT_ASSIGNED";
    private static final String REASON_NEGATIVE_PARTITION = "ROUTE_PARTITION_NEGATIVE";
    private static final int KAFKA_RECORD_OVERHEAD_BYTES = 1024;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final UiKafkaTopicProperties topicProperties;
    private final UiKafkaPublishProperties publishProperties;
    private final UiGatewayEqpRoutePartitionLookupPort routePartitionLookupPort;
    private final ObjectMapper objectMapper;

    /**
     * 필수 의존성을 주입받습니다.
     *
     * @param kafkaTemplate Kafka 발행 템플릿
     * @param topicProperties 토픽 설정
     * @param publishProperties 발행 정책 설정
     * @param routePartitionLookupPort eqpId -> route_partition 조회 포트
     * @param objectMapper 메시지 크기 사전 계산용 ObjectMapper
     */
    public UiGatewayEventKafkaPublisher(
            final KafkaTemplate<String, Object> kafkaTemplate,
            final UiKafkaTopicProperties topicProperties,
            final UiKafkaPublishProperties publishProperties,
            final UiGatewayEqpRoutePartitionLookupPort routePartitionLookupPort,
            final ObjectMapper objectMapper
    ) {
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate is null");
        this.topicProperties = Objects.requireNonNull(topicProperties, "topicProperties is null");
        this.publishProperties = Objects.requireNonNull(publishProperties, "publishProperties is null");
        this.routePartitionLookupPort = Objects.requireNonNull(routePartitionLookupPort, "routePartitionLookupPort is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
    }

    /**
     * Gateway 이벤트를 발행합니다.
     *
     * <p>처리 순서:</p>
     * <ol>
     *   <li>core DTO({@link UiCommandMessage})를 Kafka 계약({@link KafkaUiTaskMessage})으로 변환</li>
     *   <li>eqpId route_partition 조회 및 유효성 검증</li>
     *   <li>메시지 크기 사전 점검(max.request.size 가드레일)</li>
     *   <li>동기 발행(send().get(timeout))</li>
     * </ol>
     *
     * @param message 발행할 UI 명령 메시지
     */
    @Override
    public void publish(final UiCommandMessage message) {
        Objects.requireNonNull(message, "message is null");

        final KafkaUiTaskMessage kafkaMessage = toKafkaMessage(message);
        final String eqpId = message.eqpId();
        final String traceId = message.traceId();
        final String eventType = message.eventType().name();
        final String source = message.source();
        final String topic = topicProperties.getGatewayEventsTopic();

        final Optional<Integer> routePartitionOpt = routePartitionLookupPort.findRoutePartition(eqpId);
        if (routePartitionOpt.isEmpty()) {
            log.error(
                    "tc.ui.events.gateway 발행 차단: route_partition 미배정. topic={}, eqpId={}, traceId={}, eventType={}, reason={}",
                    topic, eqpId, traceId, eventType, REASON_NO_ROUTE_PARTITION
            );
            throw new IllegalStateException(
                    "Gateway 발행 실패: eqpId=" + eqpId + "에 route_partition이 배정되지 않았습니다."
            );
        }

        final int routePartition = routePartitionOpt.get();
        if (routePartition < 0) {
            log.error(
                    "tc.ui.events.gateway 발행 차단: route_partition 음수. topic={}, eqpId={}, traceId={}, eventType={}, routePartition={}, reason={}",
                    topic, eqpId, traceId, eventType, routePartition, REASON_NEGATIVE_PARTITION
            );
            throw new IllegalStateException(
                    "Gateway 발행 실패: eqpId=" + eqpId + "의 route_partition=" + routePartition + "은 유효하지 않습니다."
            );
        }

        final int payloadBytes = estimatePayloadBytes(kafkaMessage);
        final int requestBytes = payloadBytes + KAFKA_RECORD_OVERHEAD_BYTES;
        if (requestBytes > publishProperties.getMaxRequestBytes()) {
            log.error(
                    "Gateway 발행 차단: 메시지 크기 초과. topic={}, eqpId={}, traceId={}, eventType={}, payloadBytes={}, requestBytes={}, maxRequestBytes={}",
                    topic, eqpId, traceId, eventType, payloadBytes, requestBytes, publishProperties.getMaxRequestBytes()
            );
            throw new UiKafkaPublishException(
                    "Gateway Kafka 발행 메시지 크기 초과",
                    "GATEWAY",
                    traceId,
                    null
            );
        }

        final ProducerRecord<String, Object> record = new ProducerRecord<>(topic, routePartition, eqpId, kafkaMessage);
        KafkaHeaderSupport.addTracingHeaders(record, traceId, eventType, source);

        log.info("tc.ui.events.gateway 발행 시도. topic={}, partition={}, eqpId={}, traceId={}, eventType={}, requestBytes={}",
                topic, routePartition, eqpId, traceId, eventType, requestBytes);

        final long sendStartNano = System.nanoTime();
        final long publishTimeoutSeconds = publishProperties.getPublishTimeoutSeconds();

        try {
            final SendResult<String, Object> sendResult = kafkaTemplate.send(record)
                    .get(publishTimeoutSeconds, TimeUnit.SECONDS);
            if (log.isDebugEnabled()) {
                log.debug(
                        "tc.ui.events.gateway 발행 완료. topic={}, partition={}, offset={}, eqpId={}, traceId={}, eventType={}, latencyMs={}",
                        topic,
                        sendResult.getRecordMetadata().partition(),
                        sendResult.getRecordMetadata().offset(),
                        eqpId,
                        traceId,
                        eventType,
                        elapsedMillis(sendStartNano)
                );
            }
        } catch (TimeoutException e) {
            log.error(
                    "tc.ui.events.gateway 발행 타임아웃. topic={}, partition={}, eqpId={}, traceId={}, eventType={}, timeoutSeconds={}",
                    topic, routePartition, eqpId, traceId, eventType, publishTimeoutSeconds, e
            );
            throw new UiKafkaPublishException("Gateway Kafka 발행 타임아웃", "GATEWAY", traceId, e);
        } catch (ExecutionException e) {
            final Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error(
                    "tc.ui.events.gateway 발행 실패. topic={}, partition={}, eqpId={}, traceId={}, eventType={}",
                    topic, routePartition, eqpId, traceId, eventType, cause
            );
            throw new UiKafkaPublishException("Gateway Kafka 발행 실패", "GATEWAY", traceId, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error(
                    "tc.ui.events.gateway 발행 인터럽트. topic={}, partition={}, eqpId={}, traceId={}, eventType={}",
                    topic, routePartition, eqpId, traceId, eventType, e
            );
            throw new UiKafkaPublishException("Gateway Kafka 발행 인터럽트", "GATEWAY", traceId, e);
        }
    }

    /**
     * core 명령 DTO를 Kafka 계약 메시지로 변환합니다.
     *
     * @param message core 명령 DTO
     * @return Kafka 계약 메시지
     */
    private static KafkaUiTaskMessage toKafkaMessage(final UiCommandMessage message) {
        final KafkaUiTaskEventType eventType = KafkaUiTaskEventType.valueOf(message.eventType().name());
        return new KafkaUiTaskMessage(
                new KafkaUiTaskMessage.KafkaUiTaskMetadata(
                        eventType.name(),
                        OffsetDateTime.now().toString(),
                        message.source(),
                        message.traceId()
                ),
                new KafkaUiTaskMessage.KafkaUiTaskData(
                        message.eqpId(),
                        message.interfaceType(),
                        message.uiMessage(),
                        message.equipmentProfile()
                )
        );
    }

    /**
     * 메시지 직렬화 바이트 크기를 계산합니다.
     *
     * @param message 계산 대상 메시지
     * @return UTF-8 JSON 직렬화 바이트 크기
     */
    private int estimatePayloadBytes(final KafkaUiTaskMessage message) {
        try {
            return objectMapper.writeValueAsBytes(message).length;
        } catch (Exception ex) {
            throw new UiKafkaPublishException(
                    "Gateway Kafka 발행 메시지 크기 계산 실패",
                    "GATEWAY",
                    message.metadata().traceId(),
                    ex
            );
        }
    }

    private static long elapsedMillis(final long startNano) {
        return Math.max(0L, (System.nanoTime() - startNano) / 1_000_000L);
    }
}
