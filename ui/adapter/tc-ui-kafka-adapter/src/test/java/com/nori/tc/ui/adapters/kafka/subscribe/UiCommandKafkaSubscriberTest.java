package com.nori.tc.ui.adapters.kafka.subscribe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nori.tc.messaging.kafka.contract.KafkaUiTaskMessage;
import com.nori.tc.messaging.kafka.contract.KafkaUiTaskReplyMessage;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.port.messaging.UiCommandIngressPort;
import com.nori.tc.ui.domain.task.UiTaskStatus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link UiCommandKafkaSubscriber} 단위 테스트입니다.
 *
 * <p>D03 정책에 따라 파싱 실패 시 DLT 발행 없이 ACK + skip 되는지,
 * 정상 메시지가 ingress로 전달되는지를 검증합니다.</p>
 */
@ExtendWith(MockitoExtension.class)
class UiCommandKafkaSubscriberTest {

    @Mock
    private UiCommandIngressPort ingressPort;
    @Mock
    private Acknowledgment acknowledgment;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private UiCommandKafkaSubscriber subscriber;

    @BeforeEach
    void setUp() {
        subscriber = new UiCommandKafkaSubscriber(ingressPort, objectMapper);
    }

    @Test
    @DisplayName("JSON 파싱 실패 시 ACK 후 ingress 미호출")
    void parsingFailure_ack_andSkipIngress() {
        final ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "tc.ui.commands",
                1,
                12L,
                "EQP-001",
                "{\"invalid-json\""
        );

        subscriber.onMessage(record, acknowledgment);

        verify(acknowledgment).acknowledge();
        verify(ingressPort, never()).handle(any());
    }

    @Test
    @DisplayName("정상 메시지는 UiCommandReply로 변환되어 ingress로 전달되고 ACK")
    void validMessage_handleIngress_andAck() throws Exception {
        final String traceId = "trace-001";
        final KafkaUiTaskReplyMessage replyMessage = new KafkaUiTaskReplyMessage(
                new KafkaUiTaskMessage.KafkaUiTaskMetadata(
                        "EQP_START_REP",
                        OffsetDateTime.now().toString(),
                        "TC-COMM-GATEWAY",
                        traceId
                ),
                new KafkaUiTaskReplyMessage.KafkaUiTaskReplyData(
                        "EQP-001",
                        "SECS",
                        "PASS",
                        null,
                        null
                )
        );

        final ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "tc.ui.commands",
                0,
                1L,
                "EQP-001",
                objectMapper.writeValueAsString(replyMessage)
        );
        final String serialized = record.value();
        assertTrue(serialized.contains("\"STATUS\""));
        assertTrue(serialized.contains("\"ERRORMSG\""));
        assertTrue(serialized.contains("\"ERRORCODE\""));

        subscriber.onMessage(record, acknowledgment);

        final ArgumentCaptor<UiCommandReply> captor = ArgumentCaptor.forClass(UiCommandReply.class);
        verify(ingressPort).handle(captor.capture());
        verify(acknowledgment).acknowledge();

        final UiCommandReply mapped = captor.getValue();
        assertEquals(traceId, mapped.traceId());
        assertEquals("TC-COMM-GATEWAY", mapped.source());
        assertEquals("EQP_START_REP", mapped.eventType());
        assertEquals("EQP-001", mapped.eqpId());
        assertEquals(UiTaskStatus.PASS, mapped.status());
        assertTrue(mapped.errorCode() == null || mapped.errorCode().isBlank());
    }
}
