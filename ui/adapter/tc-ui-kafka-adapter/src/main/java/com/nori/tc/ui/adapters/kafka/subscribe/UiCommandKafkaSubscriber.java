package com.nori.tc.ui.adapters.kafka.subscribe;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.nori.tc.logging.TcLogContext;
import com.nori.tc.messaging.kafka.contract.KafkaUiTaskReplyMessage;
import com.nori.tc.common.coordination.error.CoordinationUnavailableException;
import com.nori.tc.ui.core.coordination.UiCoordinationUnavailableException;
import com.nori.tc.ui.core.logging.UiObservationLogger;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.model.UiCommandReplyProof;
import com.nori.tc.ui.core.port.messaging.UiCommandIngressPort;
import com.nori.tc.ui.domain.task.UiTaskStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.KafkaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
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

    private final UiCommandIngressPort ingressPort;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Counter parseErrorCounter;

    /**
     * Kafka 처리 지연 Timer({@code tc.message.processing}, W3-1)입니다.
     * MeterRegistry가 없는 순수 단위 테스트에서는 null이며, 그 경우 측정 없이 처리만 수행합니다.
     * (Prometheus 노출명 = {@code tc_message_processing_seconds}.)
     */
    private final Timer messageProcessingTimer;

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
        this.messageProcessingTimer = meterRegistry == null
                ? null
                : Timer.builder("tc.message.processing")
                        .description("Inbound Kafka message processing latency")
                        .publishPercentileHistogram()
                        .register(meterRegistry);
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
        final Timer.Sample sample = messageProcessingTimer == null ? null : Timer.start(meterRegistry);
        try {
            process(record, ack);
        } finally {
            if (sample != null) {
                sample.stop(messageProcessingTimer);
            }
        }
    }

    /**
     * {@code tc.ui.commands} 메시지 1건의 실제 처리 로직입니다. 처리 지연 측정은 {@link #onMessage}에서 감쌉니다.
     *
     * @param record Kafka consumer record (key=eqpId, value=JSON 문자열)
     * @param ack MANUAL_IMMEDIATE ACK 핸들러
     */
    private void process(
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
        } catch (JacksonException ex) {
            handleParseFailureAndAck(record, ack, ex, "json_deserialize");
            return;
        } catch (RuntimeException ex) {
            handleParseFailureAndAck(record, ack, ex, "contract_mapping");
            return;
        }

        try (TcLogContext ignored = TcLogContext.withTraceId(commandReply.traceId())) {
            incrementReceivedCounter(commandReply.eventType());

            UiObservationLogger.logCommandReplyReceived(
                    commandReply.eventType(),
                    topic,
                    partition,
                    offset,
                    key,
                    commandReply.eqpId(),
                    commandReply.traceId(),
                    commandReply.source(),
                    commandReply.status() == null ? null : commandReply.status().name()
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
                            "tc.ui.commands 처리 중 인프라 오류 - 재시도 위임. outcome=RETRY_DELEGATED, topic={}, partition={}, offset={}, key={}, eqpId={}, traceId={}, source={}, eventType={}, status={}",
                            topic, partition, offset, key, commandReply.eqpId(), commandReply.traceId(), commandReply.source(), commandReply.eventType(), commandReply.status(), ex
                    );
                    if (ex instanceof RuntimeException runtimeEx) {
                        throw runtimeEx;
                    }
                    throw new IllegalStateException("tc.ui.commands 인프라 처리 오류", ex);
                }

                log.error(
                        "tc.ui.commands 비즈니스 처리 실패 - ACK 후 skip. outcome=FAILED_ACK_SKIPPED, topic={}, partition={}, offset={}, key={}, eqpId={}, traceId={}, source={}, eventType={}, status={}",
                        topic, partition, offset, key, commandReply.eqpId(), commandReply.traceId(), commandReply.source(), commandReply.eventType(), commandReply.status(), ex
                );
                ack.acknowledge();
                return;
            }

            ack.acknowledge();
            log.info(
                    "tc.ui.commands 처리 완료 - offset 커밋. outcome=COMMITTED, topic={}, partition={}, offset={}, key={}, eqpId={}, traceId={}, source={}, eventType={}, status={}",
                    topic, partition, offset, key, commandReply.eqpId(), commandReply.traceId(), commandReply.source(), commandReply.eventType(), commandReply.status()
            );
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
                "tc.ui.commands 파싱 실패 - ACK 후 skip. outcome=ABNORMAL_ACK_SKIPPED, stage={}, topic={}, partition={}, offset={}, key={}, eqpId={}, errorType={}, error={}",
                stage,
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                record.key(),
                cause.getClass().getSimpleName(),
                safeText(cause.getMessage())
        );
        if (log.isTraceEnabled()) {
            log.trace(
                    "tc.ui.commands 파싱 실패 원문 payload. stage={}, topic={}, partition={}, offset={}, key={}, eqpId={}, payload={}",
                    stage,
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
                    record.key(),
                    safeText(record.value()),
                    cause
            );
        }

        ack.acknowledge();
        if (log.isDebugEnabled()) {
            log.debug(
                    "tc.ui.commands 파싱 실패 레코드 ACK 완료. outcome=COMMITTED_AFTER_ABNORMAL, stage={}, topic={}, partition={}, offset={}, key={}, eqpId={}",
                    stage,
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
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
                reply.data().errorMsg(),
                toCoordinationProof(reply)
        );
    }

    /**
     * reply 의 coordination proof(v2)를 추출합니다(§10.2).
     *
     * <p>proof 는 <b>operationId 와 함께</b> 있어야 의미가 있습니다 — 어느 operation 의 증명인지 모르면
     * 원장에 기록할 수 없으므로, 둘 중 하나라도 없으면 v1 응답으로 취급합니다.</p>
     *
     * @param reply Kafka 계약 응답
     * @return proof(없으면 null)
     */
    private static UiCommandReplyProof toCoordinationProof(final KafkaUiTaskReplyMessage reply) {
        final String operationId = reply.metadata().operationId();
        final String commandState = reply.data().commandState();
        if (operationId == null || operationId.isBlank() || commandState == null || commandState.isBlank()) {
            return null;
        }
        return new UiCommandReplyProof(
                operationId,
                commandState,
                reply.data().serviceSessionId(),
                reply.data().ownerEpoch(),
                reply.data().appliedRevision(),
                reply.data().desiredRevision(),
                reply.data().payloadSha256()
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
                    "알 수 없는 STATUS 값 - FAIL로 보정. outcome=ABNORMAL_DEFAULTED, traceId={}, eventType={}, status={}",
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
        /*
         * §13.5 — 조정 원장에 proof 를 기록하지 못한 것은 업무 실패가 아니라 인프라 실패다. 여기서
         * ACK 하면 그 reply 는 영구히 사라지고 operation 은 열린 채로 남는다.
         */
        if (ex instanceof UiCoordinationUnavailableException
                || ex.getCause() instanceof UiCoordinationUnavailableException
                || ex instanceof CoordinationUnavailableException
                || ex.getCause() instanceof CoordinationUnavailableException) {
            return true;
        }
        if (ex instanceof KafkaException || ex.getCause() instanceof KafkaException) {
            return true;
        }
        if (ex instanceof RedisConnectionFailureException
                || ex.getCause() instanceof RedisConnectionFailureException
                || ex instanceof QueryTimeoutException
                || ex.getCause() instanceof QueryTimeoutException) {
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

}
