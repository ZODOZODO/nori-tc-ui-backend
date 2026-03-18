package com.nori.tc.ui.adapters.kafka.subscribe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nori.tc.messaging.kafka.contract.KafkaUiTaskReplyMessage;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.port.messaging.UiCommandIngressPort;
import com.nori.tc.ui.domain.task.UiTaskStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.KafkaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * {@code tc.ui.commands} 토픽을 구독하는 UI Command 수신 어댑터입니다.
 *
 * <p>핵심 정책:</p>
 * <ul>
 *   <li>파싱 실패(JSON/계약 매핑 오류): WARN + parse_error 메트릭 + ACK</li>
 *   <li>비즈니스 처리 실패: ERROR 로그 후 ACK</li>
 *   <li>인프라성 실패(Kafka 컨테이너/브로커): 예외 재전파(컨테이너 재시도 위임)</li>
 * </ul>
 */
@Component
public class UiCommandKafkaSubscriber {

    private static final Logger log = LoggerFactory.getLogger(UiCommandKafkaSubscriber.class);
    private static final String TRACE_ID_MDC_KEY = "traceId";

    private final UiCommandIngressPort ingressPort;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Counter parseErrorCounter;

    /**
     * 테스트/로컬 조립 편의를 위한 생성자입니다.
     *
     * <p>메트릭이 필요 없는 환경에서는 MeterRegistry를 생략할 수 있도록
     * 내부적으로 null 레지스트리 생성자로 위임합니다.</p>
     *
     * @param ingressPort tc.ui.commands 수신 처리 포트
     * @param objectMapper JSON 역직렬화용 ObjectMapper
     */
    public UiCommandKafkaSubscriber(
            final UiCommandIngressPort ingressPort,
            final ObjectMapper objectMapper
    ) {
        this(ingressPort, objectMapper, null);
    }

    /**
     * Micrometer 메트릭까지 포함한 생성자입니다.
     *
     * <p>운영 애플리케이션에서는 Spring이 {@link MeterRegistry}를 주입하고,
     * 순수 단위 테스트에서는 null로 전달해 메트릭 로직만 비활성화할 수 있습니다.</p>
     *
     * @param ingressPort tc.ui.commands 수신 처리 포트
     * @param objectMapper JSON 역직렬화용 ObjectMapper
     * @param meterRegistry Micrometer 레지스트리 (없으면 null)
     */
    @Autowired
    public UiCommandKafkaSubscriber(
            final UiCommandIngressPort ingressPort,
            final ObjectMapper objectMapper,
            final MeterRegistry meterRegistry
    ) {
        this.ingressPort = Objects.requireNonNull(ingressPort, "ingressPort is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
        this.meterRegistry = meterRegistry;
        this.parseErrorCounter = meterRegistry == null
                ? null
                : meterRegistry.counter("kafka.command.parse_error");
    }

    /**
     * {@code tc.ui.commands} 토픽 메시지를 수신하여 처리합니다.
     *
     * @param record Kafka consumer record (key=eqpId, value=JSON 문자열)
     * @param ack MANUAL_IMMEDIATE ACK 핸들러
     */
    @KafkaListener(
            topics = "${tc.ui.backend.kafka.commands-topic}",
            groupId = "tc-ui-backend-group",
            containerFactory = "uiCommandListenerContainerFactory"
    )
    public void onMessage(
            final ConsumerRecord<String, String> record,
            final Acknowledgment ack
    ) {
        final String topic = record.topic();
        final int partition = record.partition();
        final long offset = record.offset();
        final String key = record.key();

        final UiCommandReply commandReply;
        try {
            final KafkaUiTaskReplyMessage rawReply =
                    objectMapper.readValue(record.value(), KafkaUiTaskReplyMessage.class);
            commandReply = toCommandReply(rawReply);
        } catch (JsonProcessingException ex) {
            handleParseFailureAndAck(record, ack, ex, "json_deserialize");
            return;
        } catch (RuntimeException ex) {
            handleParseFailureAndAck(record, ack, ex, "contract_mapping");
            return;
        }

        try (MdcTraceScope ignored = openTraceMdcScope(commandReply.traceId())) {
            incrementReceivedCounter(commandReply.eventType());

            log.info(
                    "tc.ui.commands 수신. topic={}, partition={}, offset={}, eqpId={}, traceId={}, eventType={}, source={}",
                    topic, partition, offset, key, commandReply.traceId(), commandReply.eventType(), commandReply.source()
            );
            if (log.isTraceEnabled()) {
                log.trace(
                        "tc.ui.commands 원문 payload 관측. topic={}, partition={}, offset={}, key={}, payload={}",
                        topic, partition, offset, key, safeText(record.value())
                );
            }

            try {
                ingressPort.handle(commandReply);
            } catch (Exception ex) {
                if (isInfrastructureException(ex)) {
                    log.error(
                            "tc.ui.commands 처리 중 인프라 오류 - 재시도 위임. topic={}, partition={}, offset={}, traceId={}, eventType={}",
                            topic, partition, offset, commandReply.traceId(), commandReply.eventType(), ex
                    );
                    if (ex instanceof RuntimeException runtimeEx) {
                        throw runtimeEx;
                    }
                    throw new IllegalStateException("tc.ui.commands 인프라 처리 오류", ex);
                }

                log.error(
                        "tc.ui.commands 비즈니스 처리 실패 - ACK 후 skip. topic={}, partition={}, offset={}, traceId={}, eventType={}",
                        topic, partition, offset, commandReply.traceId(), commandReply.eventType(), ex
                );
                ack.acknowledge();
                return;
            }

            ack.acknowledge();
            if (log.isDebugEnabled()) {
                log.debug(
                        "tc.ui.commands 처리 완료 - offset 커밋. topic={}, partition={}, offset={}, traceId={}, eventType={}",
                        topic, partition, offset, commandReply.traceId(), commandReply.eventType()
                );
            }
        }
    }

    /**
     * 파싱/계약 매핑 실패를 공통 정책으로 처리합니다.
     *
     * <p>정책은 D03 설계 기준에 따라 고정됩니다.</p>
     * <ul>
     *   <li>WARN 로그 기록</li>
     *   <li>{@code kafka.command.parse_error} 메트릭 증가</li>
     *   <li>현재 레코드 ACK 후 종료</li>
     * </ul>
     *
     * @param record 원본 수신 레코드
     * @param ack MANUAL_IMMEDIATE ACK 핸들러
     * @param cause 파싱 실패 원인
     * @param stage 실패 단계 식별자(json_deserialize / contract_mapping)
     */
    private void handleParseFailureAndAck(
            final ConsumerRecord<String, String> record,
            final Acknowledgment ack,
            final Throwable cause,
            final String stage
    ) {
        incrementParseErrorCounter();

        log.warn(
                "tc.ui.commands 파싱 실패 - ACK 후 skip. stage={}, topic={}, partition={}, offset={}, key={}, errorType={}, error={}",
                stage,
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                cause.getClass().getSimpleName(),
                safeText(cause.getMessage())
        );
        if (log.isTraceEnabled()) {
            log.trace(
                    "tc.ui.commands 파싱 실패 원문 payload. stage={}, topic={}, partition={}, offset={}, key={}, payload={}",
                    stage,
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
                    safeText(record.value()),
                    cause
            );
        }

        ack.acknowledge();
        if (log.isDebugEnabled()) {
            log.debug(
                    "tc.ui.commands 파싱 실패 레코드 ACK 완료. stage={}, topic={}, partition={}, offset={}, key={}",
                    stage,
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key()
            );
        }
    }

    /**
     * Kafka 계약 응답을 core 기술 중립 DTO로 변환합니다.
     *
     * @param reply Kafka 계약 응답
     * @return 변환된 UiCommandReply
     */
    private UiCommandReply toCommandReply(final KafkaUiTaskReplyMessage reply) {
        final UiTaskStatus status = parseStatusOrFail(
                reply.metadata().traceId(),
                reply.metadata().eventType(),
                reply.data().status()
        );
        return new UiCommandReply(
                reply.metadata().traceId(),
                reply.metadata().source(),
                reply.metadata().eventType(),
                reply.data().eqpId(),
                reply.data().interfaceType(),
                status,
                reply.data().errorCode(),
                reply.data().errorMsg()
        );
    }

    /**
     * 문자열 STATUS 값을 UiTaskStatus로 변환합니다.
     *
     * @param traceId 로그 추적용 traceId
     * @param eventType 로그 추적용 eventType
     * @param rawStatus 원본 STATUS 문자열
     * @return 변환된 UiTaskStatus (미지원 값은 FAIL로 보정)
     */
    private UiTaskStatus parseStatusOrFail(
            final String traceId,
            final String eventType,
            final String rawStatus
    ) {
        try {
            return UiTaskStatus.valueOf(rawStatus.trim().toUpperCase());
        } catch (Exception ex) {
            log.warn(
                    "알 수 없는 STATUS 값 - FAIL로 보정. traceId={}, eventType={}, status={}",
                    safeText(traceId), safeText(eventType), safeText(rawStatus)
            );
            return UiTaskStatus.FAIL;
        }
    }

    /**
     * 인프라성 예외 여부를 판별합니다.
     *
     * @param ex 판별 대상 예외
     * @return 인프라성 예외면 true
     */
    private static boolean isInfrastructureException(final Throwable ex) {
        if (ex instanceof KafkaException || ex.getCause() instanceof KafkaException) {
            return true;
        }
        // Spring Kafka 버전에 따라 ContainerStoppedException 타입이 노출되지 않을 수 있으므로
        // 클래스명 문자열로 방어적으로 판별합니다.
        final String className = ex.getClass().getSimpleName();
        final String causeClassName = ex.getCause() == null ? "" : ex.getCause().getClass().getSimpleName();
        return "ContainerStoppedException".equals(className)
                || "ContainerStoppedException".equals(causeClassName);
    }

    /**
     * null/blank 값을 로그용 문자열로 정규화합니다.
     *
     * @param value 원본 문자열
     * @return null-safe 문자열
     */
    private static String safeText(final String value) {
        return (value == null || value.isBlank()) ? "N/A" : value.trim();
    }

    /**
     * `kafka.command.received` 메트릭을 eventType 태그와 함께 증가시킵니다.
     *
     * @param eventType 수신 이벤트 타입
     */
    private void incrementReceivedCounter(final String eventType) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter("kafka.command.received", "eventType", safeTag(eventType)).increment();
    }

    /**
     * `kafka.command.parse_error` 메트릭을 증가시킵니다.
     */
    private void incrementParseErrorCounter() {
        if (parseErrorCounter == null) {
            return;
        }
        parseErrorCounter.increment();
    }

    /**
     * Micrometer 태그 값으로 안전하게 사용할 문자열을 정규화합니다.
     *
     * @param raw 원본 문자열
     * @return 공백/NULL을 치환한 태그 문자열
     */
    private static String safeTag(final String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.trim();
    }

    /**
     * Kafka 소비 처리 중 traceId MDC를 주입/복구하는 스코프입니다.
     *
     * @param traceId Kafka 메시지 traceId
     * @return try-with-resources용 스코프
     */
    private static MdcTraceScope openTraceMdcScope(final String traceId) {
        final String previousTraceId = MDC.get(TRACE_ID_MDC_KEY);
        if (traceId == null || traceId.isBlank()) {
            MDC.remove(TRACE_ID_MDC_KEY);
        } else {
            MDC.put(TRACE_ID_MDC_KEY, traceId);
        }
        return new MdcTraceScope(previousTraceId);
    }

    /**
     * MDC traceId 복구를 담당하는 AutoCloseable 스코프입니다.
     *
     * @param previousTraceId 스코프 진입 전 traceId
     */
    private record MdcTraceScope(String previousTraceId) implements AutoCloseable {
        @Override
        public void close() {
            if (previousTraceId == null || previousTraceId.isBlank()) {
                MDC.remove(TRACE_ID_MDC_KEY);
                return;
            }
            MDC.put(TRACE_ID_MDC_KEY, previousTraceId);
        }
    }
}
