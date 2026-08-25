package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationAcceptance;
import com.nori.tc.ui.core.coordination.model.UiOperationRequest;
import com.nori.tc.ui.core.coordination.model.UiResourceHeadView;
import com.nori.tc.ui.core.coordination.model.UiRuntimeDescriptor;
import com.nori.tc.ui.core.coordination.port.UiResourceHeadPort;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.port.db.EqpManageQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 설비 mutation 을 조정 원장 operation 으로 접수합니다(§13.2/§11.2).
 *
 * <p>여기서는 <b>DB 도 runtime 도 바꾸지 않습니다</b> — validation 과 원장 생성이 끝나기 전에는
 * Kafka publish 조차 하지 않는다는 §13.2 를 지키기 위함이며, 실제 실행은 executor 가 lease 아래에서
 * 수행합니다.</p>
 */
public final class CoordinatedUiEqpMutationCoordinator implements UiEqpMutationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(CoordinatedUiEqpMutationCoordinator.class);

    private final EqpManageQueryPort eqpManageQueryPort;
    private final UiResourceKeyPlanner resourceKeyPlanner;
    private final UiEqpDescriptorFactory descriptorFactory;
    private final UiResourceHeadPort resourceHeadPort;
    private final UiOperationAcceptanceService acceptanceService;
    private final UiOperationReconciler reconciler;
    private final ObjectMapper objectMapper;

    /**
     * 조정 coordinator 를 만듭니다.
     *
     * @param eqpManageQueryPort 설비 스냅샷 조회 포트
     * @param resourceKeyPlanner 영향 집합 산출기
     * @param descriptorFactory descriptor 팩토리
     * @param resourceHeadPort 리소스 head 포트
     * @param acceptanceService 접수 서비스
     * @param reconciler operation reconciler
     * @param objectMapper DB 확정 명령 직렬화기
     */
    public CoordinatedUiEqpMutationCoordinator(
            final EqpManageQueryPort eqpManageQueryPort,
            final UiResourceKeyPlanner resourceKeyPlanner,
            final UiEqpDescriptorFactory descriptorFactory,
            final UiResourceHeadPort resourceHeadPort,
            final UiOperationAcceptanceService acceptanceService,
            final UiOperationReconciler reconciler,
            final ObjectMapper objectMapper
    ) {
        this.eqpManageQueryPort = Objects.requireNonNull(eqpManageQueryPort, "eqpManageQueryPort is null");
        this.resourceKeyPlanner = Objects.requireNonNull(resourceKeyPlanner, "resourceKeyPlanner is null");
        this.descriptorFactory = Objects.requireNonNull(descriptorFactory, "descriptorFactory is null");
        this.resourceHeadPort = Objects.requireNonNull(resourceHeadPort, "resourceHeadPort is null");
        this.acceptanceService = Objects.requireNonNull(acceptanceService, "acceptanceService is null");
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
    }

    @Override
    public boolean appliesImmediately() {
        return false;
    }

    @Override
    public UiOperationAcceptance create(
            final EqpManagementCommand.Create command,
            final String idempotencyKey
    ) {
        final UiRuntimeDescriptor target = descriptorFactory.forTarget(
                command.eqpId(),
                null,
                command.modelVersionKey(),
                command.routePartition(),
                command.businessJarRef(),
                null,
                null,
                UiRuntimeDescriptor.GATEWAY_STATE_ENDED,
                null);
        return accept(
                UiCommandEventType.EQP_CREATE,
                command.eqpId(),
                command.actor(),
                interfaceTypeOf(command.interfaceType() == null ? null : command.interfaceType().name()),
                target,
                null,
                Set.of(command.modelVersionKey()),
                command.businessJarRef(),
                UiOperationRequest.DB_COMMAND_CREATE,
                objectMapper.writeValueAsString(command),
                idempotencyKey);
    }

    @Override
    public UiOperationAcceptance update(
            final String eqpId,
            final EqpManagementCommand.Update command,
            final String idempotencyKey
    ) {
        final EqpManagementSnapshot before = requireSnapshot(eqpId);
        final boolean modelChanged = command.modelVersionKey() != before.eqp().modelVersionKey();
        final boolean jarChanged = command.businessJarRef() != null
                && !command.businessJarRef().equals(before.eqp().businessJarKey());
        final UiCommandEventType eventType = modelChanged
                ? UiCommandEventType.EQP_MODEL_UPDATE
                : (jarChanged ? UiCommandEventType.EQP_BUSINESS_JARFILE_UPDATE : UiCommandEventType.EQP_UPDATE);

        /*
         * §23.2 — 바뀌지 않은 Gateway 필드는 descriptor 에 싣지 않는다. 실으면 model 파라미터만 바뀌는
         * 변경까지 Gateway 를 participant 로 만들고, Gateway 는 할 일이 없는 채로 proof 를 요구받는다.
         */
        final Integer routePartitionChange =
                java.util.Objects.equals(command.routePartition(), before.eqp().routePartition())
                        ? null
                        : command.routePartition();

        final UiRuntimeDescriptor target = descriptorFactory.forTarget(
                eqpId,
                before,
                command.modelVersionKey(),
                routePartitionChange,
                command.businessJarRef(),
                before.businessJar() == null ? null : before.businessJar().jarFileName(),
                null,
                null,
                null);

        final Set<Long> affectedModelVersionKeys = new LinkedHashSet<>();
        affectedModelVersionKeys.add(command.modelVersionKey());
        affectedModelVersionKeys.add(before.eqp().modelVersionKey());

        return accept(
                eventType,
                eqpId,
                command.actor(),
                interfaceTypeOf(before.eqp().commInterface() == null ? null : before.eqp().commInterface().name()),
                target,
                previousDescriptorOf(eqpId, before),
                affectedModelVersionKeys,
                command.businessJarRef(),
                UiOperationRequest.DB_COMMAND_UPDATE,
                objectMapper.writeValueAsString(command),
                idempotencyKey);
    }

    @Override
    public UiOperationAcceptance delete(final String eqpId, final String idempotencyKey) {
        final EqpManagementSnapshot before = requireSnapshot(eqpId);
        final UiRuntimeDescriptor tombstone = descriptorFactory.forDelete(eqpId, before);
        return accept(
                UiCommandEventType.EQP_DELETE,
                eqpId,
                "SYSTEM",
                interfaceTypeOf(before.eqp().commInterface() == null ? null : before.eqp().commInterface().name()),
                tombstone,
                previousDescriptorOf(eqpId, before),
                Set.of(before.eqp().modelVersionKey()),
                before.eqp().businessJarKey(),
                UiOperationRequest.DB_COMMAND_DELETE,
                "{}",
                idempotencyKey);
    }

    /**
     * 변경 전 상태를 descriptor 로 고정합니다(§13.7 expected old 판정 / §13.8 보상 원본).
     */
    private UiRuntimeDescriptor previousDescriptorOf(final String eqpId, final EqpManagementSnapshot before) {
        return descriptorFactory.forTarget(
                eqpId,
                before,
                before.eqp().modelVersionKey(),
                before.eqp().routePartition(),
                before.eqp().businessJarKey(),
                before.businessJar() == null ? null : before.businessJar().jarFileName(),
                null,
                null,
                null);
    }

    private UiOperationAcceptance accept(
            final UiCommandEventType eventType,
            final String eqpId,
            final String actor,
            final String interfaceType,
            final UiRuntimeDescriptor target,
            final UiRuntimeDescriptor previousTarget,
            final Set<Long> affectedModelVersionKeys,
            final Long businessJarKey,
            final String dbCommandKind,
            final String dbCommandJson,
            final String idempotencyKey
    ) {
        final List<String> resourceKeys =
                resourceKeyPlanner.plan(eventType, eqpId, affectedModelVersionKeys);

        final Map<String, UiRuntimeDescriptor> descriptors = new LinkedHashMap<>();
        final Map<String, Long> expectedBaseRevisions = new LinkedHashMap<>();
        for (final String resourceKey : resourceKeys) {
            final String affectedEqpId = UiResourceKeyPlanner.eqpIdOf(resourceKey);
            descriptors.put(resourceKey, affectedEqpId.equals(eqpId)
                    ? target
                    : sharedDescriptor(affectedEqpId, target.modelVersionKey(), businessJarKey));
            final UiResourceHeadView head =
                    resourceHeadPort.readHead(UiResourceKeyPlanner.RESOURCE_TYPE_EQUIPMENT, affectedEqpId);
            expectedBaseRevisions.put(resourceKey, head.committedRevision());
        }

        final Map<String, UiRuntimeDescriptor> previousDescriptors = new LinkedHashMap<>();
        if (previousTarget != null) {
            previousDescriptors.put(UiResourceKeyPlanner.RESOURCE_TYPE_EQUIPMENT + ":" + eqpId, previousTarget);
        }

        final UiOperationRequest request = new UiOperationRequest(
                idempotencyKey,
                eventType.name(),
                eqpId,
                actor,
                null,
                resourceKeys,
                descriptors,
                expectedBaseRevisions,
                null,
                null,
                requiresGatewayParticipant(target),
                interfaceType,
                previousDescriptors,
                dbCommandKind,
                dbCommandJson);

        final UiOperationAcceptance acceptance = acceptanceService.accept(request, System.currentTimeMillis());
        if (acceptance.created()) {
            // 접수 직후 한 번 전진시켜 첫 단계 지연을 reconcile 주기만큼 기다리지 않게 한다.
            reconciler.reconcileOne(acceptance.operationId());
        }
        return acceptance;
    }

    private UiRuntimeDescriptor sharedDescriptor(
            final String affectedEqpId,
            final Long modelVersionKey,
            final Long businessJarKey
    ) {
        final Optional<EqpManagementSnapshot> snapshot =
                eqpManageQueryPort.findManageSnapshotByEqpId(affectedEqpId);
        return descriptorFactory.forSharedModel(
                affectedEqpId,
                snapshot.orElse(null),
                modelVersionKey,
                businessJarKey,
                snapshot.map(EqpManagementSnapshot::businessJar)
                        .map(jar -> jar == null ? null : jar.jarFileName())
                        .orElse(null));
    }

    /**
     * Gateway owner 를 participant 로 요구할지 판정합니다(§23.2).
     *
     * <p>descriptor 가 Gateway 가 읽는 필드를 하나라도 바꾸면 Gateway 참여가 필요합니다. 반대로 model
     * 파라미터만 바뀌는 non-PLC 변경은 Gateway proof 를 요구하지 않습니다 — 요구하면 Gateway 가 할 일이
     * 없는 operation 이 gateway proof 를 영원히 기다립니다.</p>
     */
    private static boolean requiresGatewayParticipant(final UiRuntimeDescriptor descriptor) {
        return descriptor.routePartition() != null
                || descriptor.gatewayDesiredState() != null
                || descriptor.plcEnabledPollGroups() != null
                || descriptor.deleted();
    }

    private EqpManagementSnapshot requireSnapshot(final String eqpId) {
        return eqpManageQueryPort.findManageSnapshotByEqpId(eqpId)
                .orElseThrow(() -> new UiNotFoundException("설비를 찾을 수 없습니다. eqpId=" + eqpId));
    }

    private static String interfaceTypeOf(final String rawInterfaceType) {
        return rawInterfaceType == null || rawInterfaceType.isBlank() ? "SOCKET" : rawInterfaceType;
    }
}
