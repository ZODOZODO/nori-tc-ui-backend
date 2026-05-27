package com.nori.tc.ui.adapters.kafka.publish;

import tools.jackson.databind.ObjectMapper;
import com.nori.tc.messaging.kafka.contract.KafkaHeaderSupport;
import com.nori.tc.messaging.kafka.contract.KafkaUiTaskEventType;
import com.nori.tc.messaging.kafka.contract.KafkaUiTaskMessage;
import com.nori.tc.ui.adapters.kafka.config.UiKafkaPublishProperties;
import com.nori.tc.ui.adapters.kafka.config.UiKafkaTopicProperties;
import com.nori.tc.ui.adapters.kafka.exception.UiKafkaPublishException;
import com.nori.tc.ui.core.logging.UiObservationLogger;
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

        final String eqpId = message.eqpId();
        final Optional<Integer> routePartitionOpt = routePartitionLookupPort.findRoutePartition(eqpId);
        if (routePartitionOpt.isEmpty()) {
            log.error(
                    "tc.ui.events.gateway 발행 차단: outcome=REJECTED, eqpId={}, traceId={}, eventType={}, reason={}",
                    eqpId, message.traceId(), message.eventType().name(), REASON_NO_ROUTE_PARTITION
            );
            throw new IllegalStateException(
                    "Gateway 발행 실패: eqpId=" + eqpId + "에 route_partition이 배정되지 않았습니다."
            );
        }

        final int routePartition = routePartitionOpt.get();
        if (routePartition < 0) {
            log.error(
                    "tc.ui.events.gateway 발행 차단: outcome=REJECTED, eqpId={}, traceId={}, eventType={}, routePartition={}, reason={}",
                    eqpId, message.traceId(), message.eventType().name(), routePartition, REASON_NEGATIVE_PARTITION
            );
            throw new IllegalStateException(
                    "Gateway 발행 실패: eqpId=" + eqpId + "의 route_partition=" + routePartition + "은 유효하지 않습니다."
            );
        }

        publishInternal(message, routePartition);
    }

    @Override
    public void publishToPartition(final UiCommandMessage message, final int partition) {
        Objects.requireNonNull(message, "message is null");
        if (partition < 0) {
            throw new IllegalArgumentException("partition must be >= 0, got " + partition);
        }
        publishInternal(message, partition);
    }

    /**
     * 명시적 partition으로 Kafka 발행을 수행하는 공통 처리 경로입니다.
     *
     * @param message 발행 메시지
     * @param partition 명시적 partition 번호
     */
    private void publishInternal(final UiCommandMessage message, final int partition) {
        final KafkaUiTaskMessage kafkaMessage = toKafkaMessage(message);
        final String eqpId = message.eqpId();
        final String traceId = message.traceId();
        final String eventType = message.eventType().name();
        final String source = message.source();
        final String key = eqpId;
        final String topic = topicProperties.getGatewayEventsTopic();

        final int payloadBytes = estimatePayloadBytes(kafkaMessage);
        final int requestBytes = payloadBytes + KAFKA_RECORD_OVERHEAD_BYTES;
        if (requestBytes > publishProperties.getMaxRequestBytes()) {
            log.error(
                    "Gateway 발행 차단: outcome=REJECTED, reasonCode=MAX_REQUEST_BYTES_EXCEEDED, topic={}, key={}, eqpId={}, traceId={}, source={}, eventType={}, payloadBytes={}, requestBytes={}, maxRequestBytes={}",
                    topic, key, eqpId, traceId, source, eventType, payloadBytes, requestBytes, publishProperties.getMaxRequestBytes()
            );
            throw new UiKafkaPublishException(
                    "Gateway Kafka 발행 메시지 크기 초과",
                    "GATEWAY",
                    traceId,
                    null
            );
        }

        final ProducerRecord<String, Object> record = new ProducerRecord<>(topic, partition, key, kafkaMessage);
        KafkaHeaderSupport.addTracingHeaders(record, traceId, eventType, source);

        UiObservationLogger.logEventSendRequested("GATEWAY", eventType, topic, key, eqpId, traceId, source);

        final long sendStartNano = System.nanoTime();
        final long publishTimeoutSeconds = publishProperties.getPublishTimeoutSeconds();

        try {
            final SendResult<String, Object> sendResult = kafkaTemplate.send(record)
                    .get(publishTimeoutSeconds, TimeUnit.SECONDS);
            log.info(
                    "tc.ui.events.gateway publish completed. outcome=PUBLISHED, topic={}, partition={}, offset={}, key={}, eqpId={}, traceId={}, source={}, eventType={}, latencyMs={}",
                    topic,
                    sendResult.getRecordMetadata().partition(),
                    sendResult.getRecordMetadata().offset(),
                    key,
                    eqpId,
                    traceId,
                    source,
                    eventType,
                    elapsedMillis(sendStartNano)
            );
        } catch (TimeoutException e) {
            log.error(
                    "tc.ui.events.gateway 발행 타임아웃. outcome=FAILED, reasonCode=PUBLISH_TIMEOUT, topic={}, partition={}, key={}, eqpId={}, traceId={}, source={}, eventType={}, timeoutSeconds={}",
                    topic, partition, key, eqpId, traceId, source, eventType, publishTimeoutSeconds, e
            );
            throw new UiKafkaPublishException("Gateway Kafka 발행 타임아웃", "GATEWAY", traceId, e);
        } catch (ExecutionException e) {
            final Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error(
                    "tc.ui.events.gateway 발행 실패. outcome=FAILED, reasonCode=PUBLISH_FAILED, topic={}, partition={}, key={}, eqpId={}, traceId={}, source={}, eventType={}",
                    topic, partition, key, eqpId, traceId, source, eventType, cause
            );
            throw new UiKafkaPublishException("Gateway Kafka 발행 실패", "GATEWAY", traceId, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error(
                    "tc.ui.events.gateway 발행 인터럽트. outcome=FAILED, reasonCode=PUBLISH_INTERRUPTED, topic={}, partition={}, key={}, eqpId={}, traceId={}, source={}, eventType={}",
                    topic, partition, key, eqpId, traceId, source, eventType, e
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
                        UiKafkaContractMapper.toGatewayEquipmentProfileSnapshot(message.equipmentProfile())
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
