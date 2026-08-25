package com.nori.tc.ui.adapters.kafka.publish;

import com.nori.tc.messaging.kafka.contract.KafkaUiTaskMessage;
import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
import com.nori.tc.ui.core.model.UiCommandCoordination;
import com.nori.tc.ui.core.model.UiCommandMessage;
import com.nori.tc.ui.core.port.messaging.UiBusinessEventPublishPort;
import com.nori.tc.ui.core.port.messaging.UiGatewayEventPublishPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §10.1 — 단계별 명령이 v2 metadata 와 함께, 필요한 대상에게만 나가는지 고정합니다.
 */
class UiOperationCommandKafkaPublisherTest {

    @Test
    @DisplayName("Gateway 참여가 필요 없는 operation 은 Gateway 로 발행하지 않는다")
    void gatewayIsNotAddressedWhenItDoesNotParticipate() {
        final RecordingPublisher gateway = new RecordingPublisher();
        final RecordingPublisher business = new RecordingPublisher();

        new UiOperationCommandKafkaPublisher(gateway, business)
                .publishPrepare("op-1", manifest(false), 7L, "a".repeat(64), "2026-08-25T00:00:00Z");

        assertEquals(1, business.messages.size());
        assertTrue(gateway.messages.isEmpty(),
                "§23.2 — 할 일이 없는 Gateway 에 명령을 보내면 응답을 영원히 기다립니다.");
    }

    @Test
    @DisplayName("Gateway 참여가 필요하면 양쪽에 발행한다")
    void gatewayIsAddressedWhenItParticipates() {
        final RecordingPublisher gateway = new RecordingPublisher();
        final RecordingPublisher business = new RecordingPublisher();

        new UiOperationCommandKafkaPublisher(gateway, business)
                .publishApply("op-1", manifest(true), 7L, "a".repeat(64), "2026-08-25T00:00:00Z");

        assertEquals(1, gateway.messages.size());
        assertEquals(1, business.messages.size());
    }

    @Test
    @DisplayName("PREPARE 와 APPLY 는 서로 다른 traceId 를 쓰고 phase 를 구분한다")
    void phasesUseDistinctTraceIds() {
        final RecordingPublisher gateway = new RecordingPublisher();
        final RecordingPublisher business = new RecordingPublisher();
        final UiOperationCommandKafkaPublisher publisher =
                new UiOperationCommandKafkaPublisher(gateway, business);

        publisher.publishPrepare("op-1", manifest(true), 7L, "a".repeat(64), "deadline");
        publisher.publishApply("op-1", manifest(true), 7L, "a".repeat(64), "deadline");

        final UiCommandMessage prepare = business.messages.get(0);
        final UiCommandMessage apply = business.messages.get(1);

        assertNotEquals(prepare.traceId(), apply.traceId(),
                "같은 traceId 를 쓰면 result ledger 가 APPLY 를 PREPARE 의 replay 로 재현합니다.");
        assertEquals(UiCommandCoordination.PHASE_PREPARE, prepare.coordination().phase());
        assertEquals(UiCommandCoordination.PHASE_APPLY, apply.coordination().phase());
        assertEquals(7L, apply.coordination().desiredRevision());
        assertEquals(List.of("equipment:EQP001"), apply.coordination().resourceKeys());
    }

    @Test
    @DisplayName("coordination 이 있으면 Kafka metadata 는 v2 로 나간다")
    void coordinationBecomesV2Metadata() {
        final UiCommandCoordination coordination = new UiCommandCoordination(
                "op-1", "exec-1", UiCommandCoordination.PHASE_APPLY, 0L, 7L,
                "a".repeat(64), List.of("equipment:EQP001"), "deadline");

        final KafkaUiTaskMessage.KafkaUiTaskMetadata metadata =
                UiKafkaContractMapper.toMetadata("EQP_UPDATE", "TC-UI-APP", "trace", coordination);

        assertTrue(metadata.isCoordinationV2());
        assertEquals("op-1", metadata.operationId());
        assertEquals(7L, metadata.desiredRevision());
        assertEquals(List.of("equipment:EQP001"), metadata.resourceKeys());
    }

    @Test
    @DisplayName("coordination 이 없으면 v1 metadata 로 나간다")
    void noCoordinationStaysV1() {
        final KafkaUiTaskMessage.KafkaUiTaskMetadata metadata =
                UiKafkaContractMapper.toMetadata("EQP_START", "TC-UI-APP", "trace", null);

        assertTrue(!metadata.isCoordinationV2());
    }

    private static UiOperationManifestView manifest(final boolean requiresGateway) {
        return new UiOperationManifestView(
                "EQP_UPDATE", "EQP001", "tester", "exec-1", "SOCKET", requiresGateway,
                List.of("equipment:EQP001"),
                Map.of("equipment:EQP001", "{\"eqpId\":\"EQP001\"}"),
                Map.of("equipment:EQP001", 0L),
                null, null, Map.of(), null, null);
    }

    /** 발행 메시지를 붙잡아 두는 대역입니다. */
    private static final class RecordingPublisher
            implements UiGatewayEventPublishPort, UiBusinessEventPublishPort {

        private final List<UiCommandMessage> messages = new ArrayList<>();

        @Override
        public void publish(final UiCommandMessage message) {
            messages.add(message);
        }
    }
}
