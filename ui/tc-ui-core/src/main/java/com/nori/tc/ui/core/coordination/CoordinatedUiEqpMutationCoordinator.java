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
import com.nori.tc.ui.core.port.db.BusinessJarDigestPort;
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
    private final BusinessJarDigestPort businessJarDigestPort;
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
     * @param businessJarDigestPort Business JAR digest 조회 포트
     * @param resourceKeyPlanner 영향 집합 산출기
     * @param descriptorFactory descriptor 팩토리
     * @param resourceHeadPort 리소스 head 포트
     * @param acceptanceService 접수 서비스
     * @param reconciler operation reconciler
     * @param objectMapper DB 확정 명령 직렬화기
     */
    public CoordinatedUiEqpMutationCoordinator(
            final EqpManageQueryPort eqpManageQueryPort,
            final BusinessJarDigestPort businessJarDigestPort,
            final UiResourceKeyPlanner resourceKeyPlanner,
            final UiEqpDescriptorFactory descriptorFactory,
            final UiResourceHeadPort resourceHeadPort,
            final UiOperationAcceptanceService acceptanceService,
            final UiOperationReconciler reconciler,
            final ObjectMapper objectMapper
    ) {
        this.eqpManageQueryPort = Objects.requireNonNull(eqpManageQueryPort, "eqpManageQueryPort is null");
        this.businessJarDigestPort = Objects.requireNonNull(businessJarDigestPort, "businessJarDigestPort is null");
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

        /*
         * §15.2 — JAR digest 는 model 공유 상태를 바꾸는 operation 에서만 싣는다. Business
         * reconciler 는 descriptor 에 digest 가 있으면 그 operation 이 modelVersionKey 공유 상태를
         * 바꾼다고 보고 영향 집합 폐쇄를 요구하는데(BusinessCandidateReconciler#requireCompleteImpactClosure),
         * 폐쇄를 계획하는 것은 sharesModelState 인 이벤트뿐이다. 평범한 EQP_UPDATE 에까지 digest 를
         * 실으면 그 설비 하나만 잠근 채 폐쇄를 요구받아 prepare 에서 거부된다.
         */
        final BusinessJarDigestPort.JarLocator targetJar = UiResourceKeyPlanner.sharesModelState(eventType)
                ? locatorOf(command.businessJarRef())
                : null;

        final UiRuntimeDescriptor target = descriptorFactory.forTarget(
                eqpId,
                before,
                command.modelVersionKey(),
                routePartitionChange,
                command.businessJarRef(),
                targetJar != null
                        ? targetJar.jarFileName()
                        : (before.businessJar() == null ? null : before.businessJar().jarFileName()),
                targetJar == null ? null : targetJar.sha256Hex(),
                null,
                null,
                null);

        final BusinessJarDigestPort.JarLocator previousJar = UiResourceKeyPlanner.sharesModelState(eventType)
                ? locatorOf(before.eqp().businessJarKey())
                : null;
        final Set<Long> affectedModelVersionKeys = new LinkedHashSet<>();
        affectedModelVersionKeys.add(command.modelVersionKey());
        affectedModelVersionKeys.add(before.eqp().modelVersionKey());

        return accept(
                eventType,
                eqpId,
                command.actor(),
                interfaceTypeOf(before.eqp().commInterface() == null ? null : before.eqp().commInterface().name()),
                target,
                previousDescriptorOf(eqpId, before, previousJar),
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
                previousDescriptorOf(eqpId, before, null),
                Set.of(before.eqp().modelVersionKey()),
                before.eqp().businessJarKey(),
                UiOperationRequest.DB_COMMAND_DELETE,
                "{}",
                idempotencyKey);
    }

    /**
     * 변경 전 상태를 descriptor 로 고정합니다(§13.7 expected old 판정 / §13.8 보상 원본).
     *
     * @param previousJar 변경 전 Business JAR locator(§15.2 대상이 아니면 null)
     */
    private UiRuntimeDescriptor previousDescriptorOf(
            final String eqpId,
            final EqpManagementSnapshot before,
            final BusinessJarDigestPort.JarLocator previousJar
    ) {
        return descriptorFactory.forTarget(
                eqpId,
                before,
                before.eqp().modelVersionKey(),
                before.eqp().routePartition(),
                before.eqp().businessJarKey(),
                previousJar != null
                        ? previousJar.jarFileName()
                        : (before.businessJar() == null ? null : before.businessJar().jarFileName()),
                previousJar == null ? null : previousJar.sha256Hex(),
                null,
                null,
                null);
    }

    /**
     * business jar locator 를 조회합니다.
     *
     * <p>키가 없거나 JAR row 가 사라졌으면 {@code null} 입니다 — 여기서 거부하지 않습니다. JAR 부재는
     * DB 확정 단계에서 참조 무결성으로 드러나고, 접수 단계에서 막으면 이미 접수된 operation 의 재조회
     * 경로까지 함께 실패합니다.</p>
     */
    private BusinessJarDigestPort.JarLocator locatorOf(final Long businessJarKey) {
        return businessJarKey == null ? null : businessJarDigestPort.findLocator(businessJarKey).orElse(null);
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
        final Map<String, UiRuntimeDescriptor> previousDescriptors = new LinkedHashMap<>();
        /*
         * 같은 JAR 을 가리키는 공유 설비가 많을 때 같은 바이트를 설비 수만큼 다시 해시하지 않는다.
         * 이 캐시는 한 번의 접수 안에서만 산다 — 더 오래 살면 JAR 교체를 못 본다.
         */
        final Map<Long, BusinessJarDigestPort.JarLocator> locatorCache = new LinkedHashMap<>();
        for (final String resourceKey : resourceKeys) {
            final String affectedEqpId = UiResourceKeyPlanner.eqpIdOf(resourceKey);
            if (affectedEqpId.equals(eqpId)) {
                descriptors.put(resourceKey, target);
                if (previousTarget != null) {
                    previousDescriptors.put(resourceKey, previousTarget);
                }
            } else {
                addSharedDescriptors(
                        resourceKey, affectedEqpId, target, businessJarKey,
                        descriptors, previousDescriptors, locatorCache);
            }
            final UiResourceHeadView head =
                    resourceHeadPort.readHead(UiResourceKeyPlanner.RESOURCE_TYPE_EQUIPMENT, affectedEqpId);
            expectedBaseRevisions.put(resourceKey, head.committedRevision());
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

    /**
     * 같은 modelVersionKey 를 공유하는 다른 설비의 descriptor 를 만듭니다.
     *
     * <p>JAR 파일명과 digest 는 <b>대상 설비의 descriptor 에서 그대로 가져옵니다</b> — 공유 설비는
     * 대상과 같은 {@code businessJarKey} 를 가리키므로, 각 설비의 옛 스냅샷에서 이름을 읽으면 key 와
     * 이름이 서로 다른 JAR 을 가리키게 됩니다. Business reconciler 는 한 modelVersionKey 를 공유하는
     * descriptor 들이 같은 digest 를 선언하도록 요구합니다(§11.1).</p>
     */
    private void addSharedDescriptors(
            final String resourceKey,
            final String affectedEqpId,
            final UiRuntimeDescriptor target,
            final Long businessJarKey,
            final Map<String, UiRuntimeDescriptor> descriptors,
            final Map<String, UiRuntimeDescriptor> previousDescriptors,
            final Map<Long, BusinessJarDigestPort.JarLocator> locatorCache
    ) {
        final EqpManagementSnapshot snapshot =
                eqpManageQueryPort.findManageSnapshotByEqpId(affectedEqpId).orElse(null);
        descriptors.put(resourceKey, descriptorFactory.forSharedModel(
                affectedEqpId,
                snapshot,
                target.modelVersionKey(),
                businessJarKey,
                target.businessJarFileName(),
                target.businessJarSha256()));

        if (snapshot == null) {
            /*
             * 이 설비의 변경 전 상태를 모르면 되돌릴 근거가 없다. previousDescriptors 를 비워 두면
             * 보상 발급기가 "전 리소스를 덮지 못한다" 를 보고 수동 복구로 남긴다(§13.8) — 절반만
             * 되돌리는 것보다 낫다.
             */
            return;
        }
        final BusinessJarDigestPort.JarLocator previousJar =
                target.businessJarSha256() == null ? null : cachedLocatorOf(snapshot.eqp().businessJarKey(), locatorCache);
        previousDescriptors.put(resourceKey, descriptorFactory.forSharedModel(
                affectedEqpId,
                snapshot,
                snapshot.eqp().modelVersionKey(),
                snapshot.eqp().businessJarKey(),
                previousJar != null
                        ? previousJar.jarFileName()
                        : (snapshot.businessJar() == null ? null : snapshot.businessJar().jarFileName()),
                previousJar == null ? null : previousJar.sha256Hex()));
    }

    private BusinessJarDigestPort.JarLocator cachedLocatorOf(
            final Long businessJarKey,
            final Map<Long, BusinessJarDigestPort.JarLocator> cache
    ) {
        if (businessJarKey == null) {
            return null;
        }
        return cache.computeIfAbsent(businessJarKey, this::locatorOf);
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
