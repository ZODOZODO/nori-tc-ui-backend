package com.nori.tc.ui.adapters.kafka.publish;

import tools.jackson.databind.ObjectMapper;
import com.nori.tc.messaging.kafka.contract.KafkaUiTaskMessage;
import com.nori.tc.ui.core.coordination.UiRuntimeDescriptorReader;
import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
import com.nori.tc.ui.core.coordination.port.UiGatewayCommandProfileFactory;
import com.nori.tc.ui.core.model.UiCommandCoordination;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.model.UiCommandMessage;
import com.nori.tc.ui.core.model.UiEquipmentProfileSnapshot;
import com.nori.tc.ui.core.port.messaging.UiBusinessEventPublishPort;
import com.nori.tc.ui.core.port.messaging.UiGatewayEqpRoutePartitionLookupPort;
import com.nori.tc.ui.core.port.messaging.UiGatewayEventPublishPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §10.1 — 단계별 명령이 v2 metadata 와 함께, 필요한 대상에게만 나가는지 고정합니다.
 */
class UiOperationCommandKafkaPublisherTest {

    /** Gateway 명령 payload 의 최소 형태입니다 — 팩토리가 만든 결과가 그대로 실리는지만 봅니다. */
    private static final UiEquipmentProfileSnapshot PROFILE = new UiEquipmentProfileSnapshot(
            7L, "EQP001", "SOCKET", null, null, "10.0.0.1", 6000,
            500L, null, null, "PASSIVE", 0, true, null, null, null, List.of(), null, List.of(),
            "2026-08-27T00:00:00Z", "ENDED", null);

    @Test
    @DisplayName("Gateway 참여가 필요 없는 operation 은 Gateway 로 발행하지 않는다")
    void gatewayIsNotAddressedWhenItDoesNotParticipate() {
        final RecordingPublisher gateway = new RecordingPublisher();
        final RecordingPublisher business = new RecordingPublisher();

        newPublisher(gateway, business)
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

        newPublisher(gateway, business)
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
                newPublisher(gateway, business);

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

    @Test
    @DisplayName("manifest 가 routePartition 을 담고 있으면 그 partition 으로 Gateway 에 발행한다")
    void gatewayIsRoutedByManifestPartition() {
        final RecordingPublisher gateway = new RecordingPublisher();
        final RecordingPublisher business = new RecordingPublisher();

        newPublisher(gateway, business)
                .publishPrepare("op-1", createManifest(0), 1L, "a".repeat(64), "deadline");

        assertEquals(List.of(0), gateway.partitions,
                "EQP_CREATE 는 tc_eqp 행이 아직 없어 DB lookup 발행 경로로는 Gateway 에 도달하지 못합니다.");
        assertTrue(gateway.messages.isEmpty(), "manifest 에 partition 이 있으면 DB lookup 경로를 타면 안 됩니다.");
    }

    @Test
    @DisplayName("manifest 에 routePartition 이 없으면 DB lookup 발행 경로를 쓴다")
    void gatewayFallsBackToLookupWhenManifestHasNoPartition() {
        final RecordingPublisher gateway = new RecordingPublisher();
        final RecordingPublisher business = new RecordingPublisher();

        newPublisher(gateway, business)
                .publishApply("op-1", manifest(true), 7L, "a".repeat(64), "deadline");

        assertEquals(1, gateway.messages.size());
        assertTrue(gateway.partitions.isEmpty());
    }

    @Test
    @DisplayName("§13.6 — deploy operation 의 명령은 wire 계약에 있는 eventType 으로 나간다")
    void deployOperationUsesWireExpressibleEventType() {
        final RecordingPublisher gateway = new RecordingPublisher();
        final RecordingPublisher business = new RecordingPublisher();

        newPublisher(gateway, business)
                .publishPrepare("op-1", deployManifest(), 7L, "a".repeat(64), "deadline");

        /*
         * EQP_REGISTER_DEPLOY 는 UI 내부의 deploy 요청 종류이고 KafkaUiTaskEventType 에 없다 —
         * 그대로 내보내면 발행 지점의 valueOf 가 예외를 던지고, phase 는 이미 PREPARING 이라
         * 그 deploy operation 은 영구히 멈춘다.
         */
        assertEquals(1, business.messages.size());
        assertEquals(UiCommandEventType.EQP_UPDATE, business.messages.get(0).eventType());
        assertTrue(wireExpressible(business.messages.get(0).eventType()),
                "wire 계약(KafkaUiTaskEventType)에 없는 eventType 은 발행 지점에서 예외가 된다.");
    }

    /**
     * 주어진 core eventType 이 Kafka wire 계약에 존재하는지 확인합니다.
     */
    private static boolean wireExpressible(final UiCommandEventType eventType) {
        try {
            com.nori.tc.messaging.kafka.contract.KafkaUiTaskEventType.valueOf(eventType.name());
            return true;
        } catch (final IllegalArgumentException ex) {
            return false;
        }
    }

    @Test
    @DisplayName("§12.3 — Gateway 명령은 manifest 가 정한 목표 profile 을 담는다")
    void gatewayCommandCarriesTheTargetEquipmentProfile() {
        final RecordingPublisher gateway = new RecordingPublisher();
        final RecordingPublisher business = new RecordingPublisher();

        newPublisher(gateway, business, manifest -> PROFILE)
                .publishApply("op-1", manifest(true), 7L, "a".repeat(64), "deadline");

        /*
         * Gateway 는 payload 의 equipmentProfile 이 없으면 context upsert 를
         * EQP_PROFILE_PAYLOAD_REQUIRED 로 거부하고 DB 에서 대신 읽지도 않는다
         * (GatewayUiRuntimeControlSupport.requireProfilePayload / resolveOrLoadContext).
         */
        assertEquals(1, gateway.messages.size());
        assertNotNull(gateway.messages.get(0).equipmentProfile(),
                "profile 을 싣지 않으면 Gateway 참여 proof 가 REJECTED 로만 도착합니다.");
        assertEquals("EQP001", gateway.messages.get(0).equipmentProfile().eqpId());
        assertNull(business.messages.get(0).equipmentProfile(),
                "Business 는 Gateway 계약 profile 을 쓰지 않습니다 — 실으면 메시지만 커집니다.");
    }

    @Test
    @DisplayName("§13.6 — Gateway 명령은 Gateway 가 처리기를 가진 eventType 으로 나간다")
    void gatewayCommandUsesEventTypeGatewayCanProcess() {
        final RecordingPublisher gateway = new RecordingPublisher();
        final RecordingPublisher business = new RecordingPublisher();

        newPublisher(gateway, business, manifest -> PROFILE)
                .publishPrepare("op-1", jarUpdateManifest(), 7L, "a".repeat(64), "deadline");

        /*
         * EQP_BUSINESS_JARFILE_UPDATE 는 Business 에만 처리기가 있다(GatewayUiTaskProcessorRegistry).
         * 그대로 Gateway 로 보내면 수신 pipeline 이 HANDLER_NOT_FOUND FAIL 로 답하고, 그 FAIL 이
         * REJECTED proof 가 되어 operation 이 실패한다.
         */
        assertEquals(UiCommandEventType.EQP_UPDATE, gateway.messages.get(0).eventType());
        /*
         * Business 에는 처리기가 있지만 그 처리기는 uiMessage 의 JAR payload 를 필수로 요구한다. 조정
         * 명령은 목표 상태를 원장 candidate 로 전달하므로 그 payload 가 없다 — 원 eventType 을 그대로
         * 보내면 payload 부재로 FAIL 하고 그 FAIL 이 REJECTED proof 가 된다.
         */
        assertEquals(UiCommandEventType.EQP_UPDATE, business.messages.get(0).eventType(),
                "manifest 만으로 처리되는 eventType 으로 좁혀야 합니다.");
    }

    /**
     * 조정 CREATE 는 Business 의 payload 필수 처리기로 가면 안 된다(로컬 격리 실측).
     *
     * <p>Business 의 {@code EQP_CREATE} 처리기는 {@code handleEqpModelSync} 로 이어져
     * {@code uiMessage.modelInfo.modelVersionKey} 와 {@code modelParams} 를 요구한다. 조정 명령에는 그
     * payload 가 없으므로 {@code MODEL_KEY_REQUIRED} FAIL → REJECTED proof → operation ABORTED 가 된다.</p>
     */
    @Test
    @DisplayName("Business 명령은 payload 없이 처리되는 eventType 으로 나간다")
    void businessCommandAvoidsPayloadRequiringHandlers() {
        final RecordingPublisher business = new RecordingPublisher();

        newPublisher(new RecordingPublisher(), business)
                .publishPrepare("op-1", createManifestWithTombstonePrevious(1), 7L, "a".repeat(64), "deadline");

        assertEquals(UiCommandEventType.EQP_UPDATE, business.messages.get(0).eventType(),
                "EQP_CREATE 를 그대로 보내면 Business 가 payload 부재로 FAIL 해 operation 이 abort 된다");
    }

    private static UiOperationCommandKafkaPublisher newPublisher(
            final RecordingPublisher gateway,
            final RecordingPublisher business
    ) {
        return newPublisher(gateway, business, UiGatewayCommandProfileFactory.none());
    }

    private static UiOperationCommandKafkaPublisher newPublisher(
            final RecordingPublisher gateway,
            final RecordingPublisher business,
            final UiGatewayCommandProfileFactory profileFactory
    ) {
        return new UiOperationCommandKafkaPublisher(
                gateway, business, new UiRuntimeDescriptorReader(new ObjectMapper()), profileFactory,
                UiGatewayEqpRoutePartitionLookupPort.noop());
    }

    /** Business JAR 만 바뀌는데 Gateway 참여도 필요한 operation 의 manifest 입니다. */
    /**
     * §12.5 — Gateway 의 owned-transition 판정은 {@code uiMessage.gatewayProfile.oldRoutePartition} 을 읽는다.
     *
     * <p>조정 명령이 이 값을 빼면 Gateway 는 옛 owner 를 알 수 없다. Gateway 는 "옛 partition 부재" 를
     * 이동 아님으로 처리하므로(생성 operation 때문에 그래야 한다), 실제 이동에서도 release proof 를 기다리지
     * 않고 bind 해 double ownership 이 열린다.</p>
     */
    @Test
    @DisplayName("route 이동 명령은 옛/새 partition 양쪽으로 발행되고 oldRoutePartition 을 싣는다")
    void gatewayCommandCarriesPreviousRoutePartition() {
        final RecordingPublisher gateway = new RecordingPublisher();

        newPublisher(gateway, new RecordingPublisher())
                .publishApply("op-1", movingManifest(2, 5), 7L, "a".repeat(64), "deadline");

        /*
         * Gateway 는 고정 partition assign 으로 구독한다. 목표 partition 으로만 보내면 옛 owner 는
         * 메시지를 아예 받지 못해 release 도 proof 도 못 하고, 새 owner 는 release 를 기다리다
         * EQP_ROUTE_MOVE_NOT_RELEASED 로 abort 된다(운영 실측).
         */
        assertEquals(List.of(2, 5), gateway.partitions,
                "이동은 옛 partition(release) 과 새 partition(bind) 양쪽으로 발행해야 한다");
        assertEquals(
                2,
                gateway.partitionedMessages.get(0).uiMessage()
                        .path("gatewayProfile").path("oldRoutePartition").asInt(),
                "옛 routePartition 이 실려야 Gateway 가 이동 순서를 강제할 수 있다");
        assertEquals(
                2,
                gateway.partitionedMessages.get(1).uiMessage()
                        .path("gatewayProfile").path("oldRoutePartition").asInt(),
                "같은 메시지를 양쪽이 받아 각자 역할(release / bind)을 정한다");
    }

    @Test
    @DisplayName("이동이 아니면 목표 partition 하나로만 발행한다")
    void nonMoveCommandPublishesOnce() {
        final RecordingPublisher gateway = new RecordingPublisher();

        newPublisher(gateway, new RecordingPublisher())
                .publishApply("op-1", movingManifest(5, 5), 7L, "a".repeat(64), "deadline");

        assertEquals(List.of(5), gateway.partitions,
                "옛/새 partition 이 같으면 중복 발행할 이유가 없다");
    }

    /**
     * 이전 상태가 tombstone 인 생성 operation 은 옛 partition 이 없다 — 지어내지 않는다.
     *
     * <p>없는 값을 실으면 Gateway 가 있지도 않은 이동의 release proof 를 기다려
     * {@code EQP_ROUTE_MOVE_NOT_RELEASED} 로 설비 생성이 영구히 막힌다.</p>
     */
    @Test
    @DisplayName("생성 operation 은 oldRoutePartition 을 싣지 않는다")
    void createCommandDoesNotInventAnOldRoutePartition() {
        final RecordingPublisher gateway = new RecordingPublisher();

        newPublisher(gateway, new RecordingPublisher())
                .publishPrepare("op-1", createManifestWithTombstonePrevious(1), 7L, "a".repeat(64), "deadline");

        assertNull(gateway.partitionedMessages.get(0).uiMessage(),
                "옛 owner 가 없는 생성 명령에 oldRoutePartition 을 실으면 이동 게이트가 잘못 걸린다");
    }

    /**
     * 생성 operation 의 manifest 입니다 — 이전 상태가 tombstone 입니다(§16.1).
     */
    private static UiOperationManifestView createManifestWithTombstonePrevious(final int routePartition) {
        return new UiOperationManifestView(
                "EQP_CREATE", "EQP001", "tester", "exec-1", "SOCKET", true,
                List.of("equipment:EQP001"),
                Map.of("equipment:EQP001",
                        "{\"eqpId\":\"EQP001\",\"routePartition\":" + routePartition + "}"),
                Map.of("equipment:EQP001", 0L),
                null, null,
                Map.of("equipment:EQP001", "{\"eqpId\":\"EQP001\",\"deleted\":true}"),
                null, null);
    }

    /**
     * routePartition 이 옛 값에서 새 값으로 바뀌는 manifest 입니다.
     */
    private static UiOperationManifestView movingManifest(
            final int oldRoutePartition,
            final int newRoutePartition
    ) {
        return new UiOperationManifestView(
                "EQP_UPDATE", "EQP001", "tester", "exec-1", "SOCKET", true,
                List.of("equipment:EQP001"),
                Map.of("equipment:EQP001",
                        "{\"eqpId\":\"EQP001\",\"routePartition\":" + newRoutePartition + "}"),
                Map.of("equipment:EQP001", 0L),
                null, null,
                Map.of("equipment:EQP001",
                        "{\"eqpId\":\"EQP001\",\"routePartition\":" + oldRoutePartition + "}"),
                null, null);
    }

    private static UiOperationManifestView jarUpdateManifest() {
        return new UiOperationManifestView(
                "EQP_BUSINESS_JARFILE_UPDATE", "EQP001", "tester", "exec-1", "SOCKET", true,
                List.of("equipment:EQP001"),
                Map.of("equipment:EQP001", "{\"eqpId\":\"EQP001\"}"),
                Map.of("equipment:EQP001", 0L),
                null, null, Map.of(), null, null);
    }

    private static UiOperationManifestView createManifest(final int routePartition) {
        return new UiOperationManifestView(
                "EQP_CREATE", "EQP001", "tester", "exec-1", "SOCKET", true,
                List.of("equipment:EQP001"),
                Map.of("equipment:EQP001",
                        "{\"eqpId\":\"EQP001\",\"routePartition\":" + routePartition + "}"),
                Map.of("equipment:EQP001", 0L),
                null, null, Map.of(), null, null);
    }

    /**
     * deploy operation 의 manifest 입니다 — eventType 이 UI 내부 종류(EQP_REGISTER_DEPLOY)입니다.
     */
    private static UiOperationManifestView deployManifest() {
        return new UiOperationManifestView(
                "EQP_REGISTER_DEPLOY", "EQP001", "tester", "exec-1", "SOCKET", false,
                List.of("equipment:EQP001"),
                Map.of("equipment:EQP001", "{\"eqpId\":\"EQP001\"}"),
                Map.of("equipment:EQP001", 0L),
                null, null, Map.of(), null, null);
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
        private final List<Integer> partitions = new ArrayList<>();
        private final List<UiCommandMessage> partitionedMessages = new ArrayList<>();

        @Override
        public void publish(final UiCommandMessage message) {
            messages.add(message);
        }

        @Override
        public void publishToPartition(final UiCommandMessage message, final int partition) {
            partitions.add(partition);
            partitionedMessages.add(message);
        }
    }
}
