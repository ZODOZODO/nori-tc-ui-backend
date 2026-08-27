package com.nori.tc.ui.adapters.kafka.publish;

import com.nori.tc.ui.core.coordination.UiRuntimeDescriptorReader;
import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
import com.nori.tc.ui.core.coordination.port.UiGatewayCommandProfileFactory;
import com.nori.tc.ui.core.coordination.port.UiOperationCommandPublisher;
import com.nori.tc.ui.core.model.UiCommandCoordination;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.model.UiCommandMessage;
import com.nori.tc.ui.core.model.UiEquipmentProfileSnapshot;
import com.nori.tc.ui.core.port.messaging.UiBusinessEventPublishPort;
import com.nori.tc.ui.core.port.messaging.UiGatewayEventPublishPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * {@link UiOperationCommandPublisher} 의 Kafka 구현입니다(§10.1 v2 metadata).
 *
 * <p>Business 는 원장 polling 으로 pending operation 을 스스로 발견하므로 이 명령이 없어도 진행하지만,
 * Gateway 는 UI 명령을 받아야 참여합니다. 그래서 <b>Gateway 참여가 필요한 operation</b> 은 두 단계
 * 모두에서 명령을 발행합니다.</p>
 *
 * <p>APPLY 단계는 PREPARE 와 <b>같은 traceId·payload</b> 를 다시 보냅니다. Gateway 의 command result
 * ledger 가 같은 traceId+digest 를 최초 결과로 재현하므로(§12.6) 부작용 없이 같은 proof 가 다시
 * 도착하고, 그 proof 가 commit 이후에 기록됩니다.</p>
 */
public final class UiOperationCommandKafkaPublisher implements UiOperationCommandPublisher {

    private static final Logger log = LoggerFactory.getLogger(UiOperationCommandKafkaPublisher.class);

    private static final String SOURCE = "TC-UI-APP";

    /**
     * Gateway 가 처리기를 등록한 명령 종류입니다({@code GatewayUiTaskProcessorRegistry}).
     *
     * <p>여기 없는 종류를 Gateway 로 보내면 수신 pipeline 이 {@code HANDLER_NOT_FOUND} FAIL 로 답하고,
     * 그 FAIL 이 REJECTED proof 가 되어 operation 이 실패합니다.</p>
     */
    private static final Set<UiCommandEventType> GATEWAY_COMMAND_EVENT_TYPES = EnumSet.of(
            UiCommandEventType.EQP_CREATE,
            UiCommandEventType.EQP_UPDATE,
            UiCommandEventType.EQP_MODEL_UPDATE,
            UiCommandEventType.EQP_DELETE,
            UiCommandEventType.EQP_GATEWAY_JARFILE_UPDATE);

    /**
     * Business 가 <b>manifest 만 가진 명령</b>으로 처리할 수 있는 종류입니다.
     *
     * <p>기준은 "처리기가 등록돼 있는가" 가 아니라 "{@code uiMessage} payload 없이 처리되는가" 입니다.
     * 조정 명령은 목표 상태를 원장 candidate 로 전달하므로 {@code uiMessage} 에 모델/파라미터/JAR
     * payload 를 담지 않습니다. 그런데 Business 의 {@code EQP_CREATE} / {@code EQP_MODEL_UPDATE} /
     * {@code EQP_PARAMETER_UPDATE} / {@code EQP_BUSINESS_JARFILE_UPDATE} 처리기는 그 payload 를
     * <b>필수</b>로 요구해 {@code MODEL_KEY_REQUIRED} 등으로 FAIL 하고, 그 FAIL 이 REJECTED proof 가
     * 되어 operation 을 실패시킵니다(로컬 격리 실측).</p>
     *
     * <p>{@code EQP_UPDATE} 는 설비 메타만 반영하므로 payload 가 없어도 성립하고, {@code EQP_DELETE} 는
     * 바인딩 제거라 payload 가 필요 없습니다. 나머지는 {@code EQP_UPDATE} 로 좁힙니다 — deploy step 의
     * 명령은 payload 를 실은 별도 발행 경로({@code DeployApplyDriver})가 담당합니다.</p>
     */
    private static final Set<UiCommandEventType> BUSINESS_COMMAND_EVENT_TYPES = EnumSet.of(
            UiCommandEventType.EQP_UPDATE,
            UiCommandEventType.EQP_DELETE);

    private final UiGatewayEventPublishPort gatewayPublishPort;
    private final UiBusinessEventPublishPort businessPublishPort;
    private final UiRuntimeDescriptorReader descriptorReader;
    private final UiGatewayCommandProfileFactory gatewayProfileFactory;

    /**
     * publisher 를 만듭니다.
     *
     * @param gatewayPublishPort gateway 이벤트 발행 포트
     * @param businessPublishPort business 이벤트 발행 포트
     * @param descriptorReader manifest descriptor 해석기(gateway 라우팅 partition 출처)
     * @param gatewayProfileFactory gateway 명령 payload(설비 profile) 팩토리
     */
    public UiOperationCommandKafkaPublisher(
            final UiGatewayEventPublishPort gatewayPublishPort,
            final UiBusinessEventPublishPort businessPublishPort,
            final UiRuntimeDescriptorReader descriptorReader,
            final UiGatewayCommandProfileFactory gatewayProfileFactory
    ) {
        this.gatewayPublishPort = Objects.requireNonNull(gatewayPublishPort, "gatewayPublishPort is null");
        this.businessPublishPort = Objects.requireNonNull(businessPublishPort, "businessPublishPort is null");
        this.descriptorReader = Objects.requireNonNull(descriptorReader, "descriptorReader is null");
        this.gatewayProfileFactory =
                Objects.requireNonNull(gatewayProfileFactory, "gatewayProfileFactory is null");
    }

    @Override
    public void publishPrepare(
            final String operationId,
            final UiOperationManifestView manifest,
            final long desiredRevision,
            final String manifestSha256,
            final String deadlineAt
    ) {
        publish(operationId, manifest, desiredRevision, manifestSha256, deadlineAt,
                UiCommandCoordination.PHASE_PREPARE);
    }

    @Override
    public void publishApply(
            final String operationId,
            final UiOperationManifestView manifest,
            final long desiredRevision,
            final String manifestSha256,
            final String deadlineAt
    ) {
        publish(operationId, manifest, desiredRevision, manifestSha256, deadlineAt,
                UiCommandCoordination.PHASE_APPLY);
    }

    private void publish(
            final String operationId,
            final UiOperationManifestView manifest,
            final long desiredRevision,
            final String manifestSha256,
            final String deadlineAt,
            final String phase
    ) {
        final UiCommandCoordination coordination = new UiCommandCoordination(
                operationId,
                manifest.executionId(),
                phase,
                manifest.expectedBaseRevisions().get(primaryResourceKey(manifest)),
                desiredRevision,
                manifestSha256,
                manifest.resourceKeys(),
                deadlineAt);

        /*
         * traceId 를 operationId+phase 로 고정한다. 같은 phase 를 재발행하면 수신 측 result
         * ledger 가 최초 결과를 replay 하고, 다른 phase 는 별개 명령으로 처리된다.
         */
        final String traceId = operationId + ":" + phase;
        businessPublishPort.publish(
                command(wireEventTypeOf(manifest, BUSINESS_COMMAND_EVENT_TYPES), traceId, manifest,
                        null, null, coordination));
        if (manifest.requiresGatewayParticipant()) {
            /*
             * §12.3/§12.4 — Gateway 는 명령 payload 의 equipmentProfile 이 없으면 context upsert 를
             * 거부하고 DB 에서 대신 읽지도 않는다. 조정 경로의 DB 확정은 commit 이후이므로 목표 상태의
             * 유일한 출처가 원장 manifest 다.
             */
            publishToGateway(
                    command(wireEventTypeOf(manifest, GATEWAY_COMMAND_EVENT_TYPES), traceId, manifest,
                            gatewayUiMessage(manifest), gatewayProfileFactory.profileFor(manifest), coordination),
                    manifest);
        }
        log.info("UI_OPERATION_COMMAND_PUBLISHED. operationId={}, phase={}, gatewayParticipant={}, revision={}",
                operationId, phase, manifest.requiresGatewayParticipant(), desiredRevision);
    }

    private UiCommandMessage command(
            final UiCommandEventType eventType,
            final String traceId,
            final UiOperationManifestView manifest,
            final JsonNode uiMessage,
            final UiEquipmentProfileSnapshot equipmentProfile,
            final UiCommandCoordination coordination
    ) {
        return new UiCommandMessage(
                eventType,
                traceId,
                SOURCE,
                manifest.eqpId(),
                manifest.interfaceType() == null ? "SOCKET" : manifest.interfaceType(),
                uiMessage,
                equipmentProfile,
                coordination);
    }

    /**
     * Gateway 명령의 {@code uiMessage} 를 만듭니다 — 옛 routePartition 만 담습니다(§12.5).
     *
     * <p>Gateway 의 owned-transition 판정은 {@code uiMessage.gatewayProfile.oldRoutePartition} 을 읽습니다
     * (직접 경로도 모든 EQP_UPDATE 에 같은 값을 주입합니다 — {@code EqpDirectScenarioService}). 조정
     * 경로가 이 값을 빼면 Gateway 는 옛 owner 를 알 수 없고, 실제 route 이동에서 <b>release proof 이전에
     * bind</b> 해 double ownership 이 열립니다.</p>
     *
     * <p>이전 상태가 tombstone 인 생성 operation 은 옛 partition 이 없으므로 담지 않습니다 — 없는 값을
     * 지어내면 Gateway 가 있지도 않은 이동을 기다립니다.</p>
     *
     * @param manifest 원장에 고정된 manifest
     * @return uiMessage 노드(옛 partition 을 모르면 null)
     */
    private JsonNode gatewayUiMessage(final UiOperationManifestView manifest) {
        final String previousJson =
                manifest.previousDescriptorJsonByResourceKey().get(primaryResourceKey(manifest));
        if (previousJson == null) {
            return null;
        }
        final UiRuntimeDescriptorReader.DescriptorFields previous = descriptorReader.read(previousJson);
        if (previous.deleted() || previous.routePartition() == null) {
            return null;
        }
        final ObjectNode uiMessage = JsonNodeFactory.instance.objectNode();
        uiMessage.putObject("gatewayProfile").put("oldRoutePartition", previous.routePartition().intValue());
        return uiMessage;
    }

    /**
     * manifest 의 eventType 을 <b>수신자가 처리기를 가진</b> 명령 eventType 으로 정합니다(§13.6).
     *
     * <p>manifest 의 eventType 은 UI 가 무엇을 하려는지를 나타내고, 수신자별 처리기 목록과 반드시
     * 일치하지 않습니다. 두 가지가 어긋납니다.</p>
     * <ul>
     *   <li>{@code EQP_REGISTER_DEPLOY} 는 {@code KafkaUiTaskEventType} 에 아예 없어 발행 지점의
     *       {@code valueOf} 가 예외를 던진다 — 그 시점 phase 는 이미 {@code PREPARING} 이라 그
     *       operation 은 리소스 claim 을 쥔 채 멈춘다.</li>
     *   <li>{@code EQP_BUSINESS_JARFILE_UPDATE} / {@code EQP_PARAMETER_UPDATE} 는 Gateway 에 처리기가
     *       없어 {@code HANDLER_NOT_FOUND} FAIL 이 되고, 그 FAIL 이 REJECTED proof 로 operation 을
     *       실패시킨다. 반대로 {@code EQP_GATEWAY_JARFILE_UPDATE} 는 Business 에 처리기가 없다.</li>
     * </ul>
     *
     * <p>어느 쪽이든 그 수신자가 해야 할 일은 목표 상태의 context 반영이고 그것이 {@code EQP_UPDATE}
     * 입니다 — deploy step 도 같은 매핑을 씁니다
     * ({@code DeployServiceCommandSupport.resolveMessageEventType}).</p>
     *
     * @param manifest 원장에 고정된 manifest
     * @param supported 수신자가 처리기를 등록한 eventType 집합
     * @return 그 수신자가 처리할 수 있는 명령 eventType
     */
    private static UiCommandEventType wireEventTypeOf(
            final UiOperationManifestView manifest,
            final Set<UiCommandEventType> supported
    ) {
        final UiCommandEventType eventType = UiCommandEventType.valueOf(manifest.eventType());
        return supported.contains(eventType) ? eventType : UiCommandEventType.EQP_UPDATE;
    }

    /**
     * Gateway 명령을 manifest 가 고정한 partition 으로 보냅니다.
     *
     * <p>기본 발행 경로는 {@code tc_eqp.route_partition} 을 조회해 partition 을 정하는데, 조정 프로토콜에서
     * 그 행은 commit 이후 DB 확정 단계에 쓰인다. 그래서 EQP_CREATE 의 PREPARE 는 아직 행이 없고,
     * EQP_DELETE 의 APPLY 는 이미 행이 지워져 조회가 비고 발행이 거부된다 — operation 은 참여 증적을
     * 영원히 기다린다. manifest descriptor 의 {@code routePartition} 은 그 operation 이 의도한 값이며
     * 원장에 고정돼 있으므로(§13.1/§13.4) 라우팅 출처로 이쪽이 옳다.</p>
     *
     * <p>descriptor 에 값이 없으면(변경 대상이 아니면 null 이다) 종전의 DB 조회 경로를 그대로 쓴다.</p>
     */
    private void publishToGateway(final UiCommandMessage message, final UiOperationManifestView manifest) {
        final String descriptorJson = manifest.descriptorJsonByResourceKey().get(primaryResourceKey(manifest));
        final Integer routePartition = descriptorJson == null
                ? null
                : descriptorReader.read(descriptorJson).routePartition();
        if (routePartition == null) {
            gatewayPublishPort.publish(message);
            return;
        }
        gatewayPublishPort.publishToPartition(message, routePartition);
    }

    private static String primaryResourceKey(final UiOperationManifestView manifest) {
        return manifest.resourceKeys().isEmpty() ? "" : manifest.resourceKeys().get(0);
    }
}
