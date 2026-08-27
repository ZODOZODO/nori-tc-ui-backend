package com.nori.tc.ui.adapters.kafka.publish;

import com.nori.tc.ui.core.coordination.UiRuntimeDescriptorReader;
import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
import com.nori.tc.ui.core.coordination.port.UiOperationCommandPublisher;
import com.nori.tc.ui.core.model.UiCommandCoordination;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.model.UiCommandMessage;
import com.nori.tc.ui.core.port.messaging.UiBusinessEventPublishPort;
import com.nori.tc.ui.core.port.messaging.UiGatewayEventPublishPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

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

    private final UiGatewayEventPublishPort gatewayPublishPort;
    private final UiBusinessEventPublishPort businessPublishPort;
    private final UiRuntimeDescriptorReader descriptorReader;

    /**
     * publisher 를 만듭니다.
     *
     * @param gatewayPublishPort gateway 이벤트 발행 포트
     * @param businessPublishPort business 이벤트 발행 포트
     * @param descriptorReader manifest descriptor 해석기(gateway 라우팅 partition 출처)
     */
    public UiOperationCommandKafkaPublisher(
            final UiGatewayEventPublishPort gatewayPublishPort,
            final UiBusinessEventPublishPort businessPublishPort,
            final UiRuntimeDescriptorReader descriptorReader
    ) {
        this.gatewayPublishPort = Objects.requireNonNull(gatewayPublishPort, "gatewayPublishPort is null");
        this.businessPublishPort = Objects.requireNonNull(businessPublishPort, "businessPublishPort is null");
        this.descriptorReader = Objects.requireNonNull(descriptorReader, "descriptorReader is null");
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

        final UiCommandMessage message = new UiCommandMessage(
                UiCommandEventType.valueOf(manifest.eventType()),
                /*
                 * traceId 를 operationId+phase 로 고정한다. 같은 phase 를 재발행하면 수신 측 result
                 * ledger 가 최초 결과를 replay 하고, 다른 phase 는 별개 명령으로 처리된다.
                 */
                operationId + ":" + phase,
                SOURCE,
                manifest.eqpId(),
                manifest.interfaceType() == null ? "SOCKET" : manifest.interfaceType(),
                null,
                null,
                coordination);

        businessPublishPort.publish(message);
        if (manifest.requiresGatewayParticipant()) {
            publishToGateway(message, manifest);
        }
        log.info("UI_OPERATION_COMMAND_PUBLISHED. operationId={}, phase={}, gatewayParticipant={}, revision={}",
                operationId, phase, manifest.requiresGatewayParticipant(), desiredRevision);
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
