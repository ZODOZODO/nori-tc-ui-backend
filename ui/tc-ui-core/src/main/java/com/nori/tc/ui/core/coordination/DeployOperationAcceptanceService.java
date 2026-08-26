package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationAcceptance;
import com.nori.tc.ui.core.coordination.model.UiOperationRequest;
import com.nori.tc.ui.core.coordination.model.UiResourceHeadView;
import com.nori.tc.ui.core.coordination.model.UiRuntimeDescriptor;
import com.nori.tc.ui.core.coordination.port.UiResourceHeadPort;
import com.nori.tc.ui.core.deploy.DeployExecutionTarget;
import com.nori.tc.ui.core.deploy.DeployResolvedStepPlan;
import com.nori.tc.ui.core.deploy.DeployTargetApp;
import com.nori.tc.ui.core.deploy.DeployTargetFinalizationCommand;
import com.nori.tc.ui.core.deploy.DeployTargetPlanDraft;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.model.UiCommandEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * deploy target 을 조정 원장 operation 으로 접수합니다(§13.6).
 *
 * <p>접수 단위는 target 이 아니라 <b>영향 폐쇄 그룹</b>입니다. {@link UiResourceKeyPlanner} 는 모델
 * 공유 상태를 바꾸는 이벤트에 대해 같은 {@code modelVersionKey} 를 쓰는 전 설비로 영향 집합을
 * 확장하는데, deploy planner 는 target 을 그렇게 확장하지 않습니다. 그래서 target 마다 operation 을
 * 만들면 같은 모델을 공유하는 target 들이 서로의 설비를 잠근 채 각자 다른 목표 상태를 쓰려 듭니다.
 * 영향 집합이 겹치는 target 은 한 operation 으로 합칩니다.</p>
 *
 * <p>여기서는 DB 도 runtime 도 바꾸지 않고 Kafka 발행도 하지 않습니다(§13.2) — 실행은 executor 가
 * lease 아래에서 수행합니다.</p>
 */
public final class DeployOperationAcceptanceService {

    private static final Logger log = LoggerFactory.getLogger(DeployOperationAcceptanceService.class);

    /** deploy operation 의 idempotency key 접두사입니다. */
    public static final String DEPLOY_KEY_PREFIX = "DEPLOY:";

    private final UiResourceKeyPlanner resourceKeyPlanner;
    private final UiDeployDescriptorFactory descriptorFactory;
    private final UiResourceHeadPort resourceHeadPort;
    private final UiOperationAcceptanceService acceptanceService;
    private final UiOperationReconciler reconciler;
    private final ObjectMapper objectMapper;

    /**
     * 접수 서비스를 만듭니다.
     *
     * @param resourceKeyPlanner 영향 집합 산출기
     * @param descriptorFactory deploy descriptor 팩토리
     * @param resourceHeadPort 리소스 head 포트
     * @param acceptanceService 공통 접수 서비스
     * @param reconciler operation reconciler
     * @param objectMapper DB 확정 명령 직렬화기
     */
    public DeployOperationAcceptanceService(
            final UiResourceKeyPlanner resourceKeyPlanner,
            final UiDeployDescriptorFactory descriptorFactory,
            final UiResourceHeadPort resourceHeadPort,
            final UiOperationAcceptanceService acceptanceService,
            final UiOperationReconciler reconciler,
            final ObjectMapper objectMapper
    ) {
        this.resourceKeyPlanner = Objects.requireNonNull(resourceKeyPlanner, "resourceKeyPlanner is null");
        this.descriptorFactory = Objects.requireNonNull(descriptorFactory, "descriptorFactory is null");
        this.resourceHeadPort = Objects.requireNonNull(resourceHeadPort, "resourceHeadPort is null");
        this.acceptanceService = Objects.requireNonNull(acceptanceService, "acceptanceService is null");
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
    }

    /**
     * 영향 집합이 겹치는 target 을 한 그룹으로 묶습니다.
     *
     * @param eventType deploy 이벤트 타입
     * @param dispatchTargets 실행 대상 target
     * @return 그룹 목록(각 그룹은 대표 target key 오름차순)
     */
    public List<DeployOperationGroup> group(
            final UiCommandEventType eventType,
            final List<DeployExecutionTarget> dispatchTargets
    ) {
        Objects.requireNonNull(eventType, "eventType is null");
        Objects.requireNonNull(dispatchTargets, "dispatchTargets is null");

        final List<DeployOperationGroup> groups = new ArrayList<>();
        for (final DeployExecutionTarget target : dispatchTargets) {
            final Set<String> closure = new LinkedHashSet<>(closureOf(eventType, target));
            /*
             * 이미 만든 그룹 중 영향 집합이 겹치는 것을 전부 흡수한다. 겹치는 그룹이 둘 이상일 수
             * 있으므로(A-B 와 C-D 를 잇는 target 이 뒤에 올 수 있다) 하나만 보고 멈추면 같은 설비를
             * 두 operation 이 잠그게 된다.
             */
            final List<DeployExecutionTarget> members = new ArrayList<>();
            members.add(target);
            groups.removeIf(existing -> {
                if (existing.resourceKeys().stream().noneMatch(closure::contains)) {
                    return false;
                }
                closure.addAll(existing.resourceKeys());
                members.addAll(existing.targets());
                return true;
            });
            groups.add(new DeployOperationGroup(sortedByTargetKey(members), sorted(closure)));
        }
        groups.sort((left, right) -> Long.compare(left.representativeTargetKey(), right.representativeTargetKey()));
        return List.copyOf(groups);
    }

    /**
     * 그룹 하나를 operation 으로 접수합니다.
     *
     * @param deployKey deploy 식별자
     * @param eventType deploy 이벤트 타입
     * @param group 접수할 그룹
     * @param actor 요청자
     * @return 접수 결과
     */
    public UiOperationAcceptance accept(
            final long deployKey,
            final UiCommandEventType eventType,
            final DeployOperationGroup group,
            final String actor
    ) {
        Objects.requireNonNull(eventType, "eventType is null");
        Objects.requireNonNull(group, "group is null");

        final Map<String, DeployExecutionTarget> targetsByResourceKey = indexByResourceKey(group);
        final ClosureState closureState = closureStateOf(group, targetsByResourceKey);

        final Map<String, UiRuntimeDescriptor> descriptors = new LinkedHashMap<>();
        final Map<String, UiRuntimeDescriptor> previousDescriptors = new LinkedHashMap<>();
        final Map<String, Long> expectedBaseRevisions = new LinkedHashMap<>();

        for (final String resourceKey : group.resourceKeys()) {
            final DeployExecutionTarget member = targetsByResourceKey.get(resourceKey);
            if (member != null) {
                final DeployTargetPlanDraft planDraft = member.target().planDraft();
                descriptors.put(resourceKey, descriptorFactory.forDeployTarget(planDraft));
                final UiRuntimeDescriptor previous = descriptorFactory.forDeployTargetPrevious(planDraft);
                if (previous != null) {
                    previousDescriptors.put(resourceKey, previous);
                }
            } else {
                descriptors.put(resourceKey, descriptorFactory.forClosureOnly(
                        UiResourceKeyPlanner.eqpIdOf(resourceKey),
                        closureState.modelVersionKey(),
                        closureState.businessJarKey()));
            }
            final UiResourceHeadView head = resourceHeadPort.readHead(
                    UiResourceKeyPlanner.RESOURCE_TYPE_EQUIPMENT, UiResourceKeyPlanner.eqpIdOf(resourceKey));
            expectedBaseRevisions.put(resourceKey, head.committedRevision());
        }

        final DeployExecutionTarget representative = group.targets().get(0);
        final String executionId = executionIdOf(deployKey, group.representativeTargetKey());
        final UiOperationRequest request = new UiOperationRequest(
                executionId,
                eventType.name(),
                representative.target().planDraft().eqpId(),
                actor,
                executionId,
                group.resourceKeys(),
                descriptors,
                expectedBaseRevisions,
                null,
                null,
                requiresGatewayParticipant(group),
                representative.target().planDraft().interfaceType(),
                previousDescriptors,
                UiOperationRequest.DB_COMMAND_DEPLOY_TARGET,
                objectMapper.writeValueAsString(new DeployTargetFinalizationCommand(
                        deployKey, targetKeysOf(group), actor)));

        final UiOperationAcceptance acceptance = acceptanceService.accept(request, System.currentTimeMillis());
        log.info("DEPLOY_OPERATION_ACCEPTED. deployKey={}, operationId={}, targetKeys={}, resourceKeys={}",
                deployKey, acceptance.operationId(), targetKeysOf(group), group.resourceKeys());
        if (acceptance.created()) {
            // 접수 직후 한 번 전진시켜 첫 단계 지연을 reconcile 주기만큼 기다리지 않게 한다.
            reconciler.reconcileOne(acceptance.operationId());
        }
        return acceptance;
    }

    /**
     * deploy 그룹의 결정적 execution/idempotency key 를 만듭니다(Plan D6/D9).
     *
     * <p>저장하지 않고 도출합니다 — 같은 deploy 와 같은 그룹이면 같은 key 가 나오므로 컬럼도 백필도
     * 필요 없고, 저장한 값이 실제 원장과 어긋날 여지도 없습니다.</p>
     *
     * @param deployKey deploy 식별자
     * @param representativeTargetKey 그룹 내 최소 target key
     * @return execution/idempotency key
     */
    public static String executionIdOf(final long deployKey, final long representativeTargetKey) {
        return DEPLOY_KEY_PREFIX + deployKey + ":" + representativeTargetKey;
    }

    private List<String> closureOf(final UiCommandEventType eventType, final DeployExecutionTarget target) {
        final DeployTargetPlanDraft planDraft = target.target().planDraft();
        final Set<Long> affectedModelVersionKeys = new LinkedHashSet<>();
        if (planDraft.currentState() != null) {
            affectedModelVersionKeys.add(planDraft.currentState().modelVersionKey());
        }
        if (planDraft.desiredState().modelVersionKey() != null) {
            affectedModelVersionKeys.add(planDraft.desiredState().modelVersionKey());
        }
        return resourceKeyPlanner.plan(eventType, planDraft.eqpId(), affectedModelVersionKeys);
    }

    /**
     * 폐쇄로만 끌려온 설비에 쓸 공유 상태를 확정합니다.
     *
     * <p>그룹 안의 target 들이 서로 다른 목표 모델/JAR 을 요구하면 그 설비들이 어떤 상태가 되어야
     * 하는지 정할 수 없습니다. 아무 값이나 골라 적용하면 배포하지도 않은 설비가 임의의 상태로
     * 바뀌므로 접수를 거부합니다. 폐쇄로 끌려온 설비가 없으면 이 판정 자체가 필요 없습니다.</p>
     */
    private ClosureState closureStateOf(
            final DeployOperationGroup group,
            final Map<String, DeployExecutionTarget> targetsByResourceKey
    ) {
        final boolean hasClosureOnly = group.resourceKeys().stream()
                .anyMatch(resourceKey -> !targetsByResourceKey.containsKey(resourceKey));
        if (!hasClosureOnly) {
            return new ClosureState(null, null);
        }

        final Set<Long> modelVersionKeys = new LinkedHashSet<>();
        final Set<Long> businessJarKeys = new LinkedHashSet<>();
        for (final DeployExecutionTarget target : group.targets()) {
            final var desired = target.target().planDraft().desiredState();
            if (desired.modelVersionKey() != null) {
                modelVersionKeys.add(desired.modelVersionKey());
            }
            if (desired.businessJarRef() != null) {
                businessJarKeys.add(desired.businessJarRef());
            }
        }
        if (modelVersionKeys.size() > 1 || businessJarKeys.size() > 1) {
            throw new UiConflictException(
                    "같은 모델을 공유하는 설비들이 서로 다른 목표 상태로 배포됩니다. 배포 대상을 나눠서 실행하세요."
                            + " resourceKeys=" + group.resourceKeys()
                            + ", modelVersionKeys=" + modelVersionKeys
                            + ", businessJarKeys=" + businessJarKeys);
        }
        return new ClosureState(
                modelVersionKeys.isEmpty() ? null : modelVersionKeys.iterator().next(),
                businessJarKeys.isEmpty() ? null : businessJarKeys.iterator().next());
    }

    private static Map<String, DeployExecutionTarget> indexByResourceKey(final DeployOperationGroup group) {
        final Map<String, DeployExecutionTarget> index = new LinkedHashMap<>();
        for (final DeployExecutionTarget target : group.targets()) {
            index.put(
                    UiResourceKeyPlanner.RESOURCE_TYPE_EQUIPMENT + ":" + target.target().planDraft().eqpId(),
                    target);
        }
        return index;
    }

    private static boolean requiresGatewayParticipant(final DeployOperationGroup group) {
        return group.targets().stream()
                .flatMap(target -> target.target().planDraft().resolvedSteps().stream())
                .map(DeployResolvedStepPlan::targetApp)
                .anyMatch(DeployTargetApp.GATEWAY::equals);
    }

    private static List<Long> targetKeysOf(final DeployOperationGroup group) {
        return group.targets().stream().map(target -> target.target().deployTargetKey()).toList();
    }

    private static List<DeployExecutionTarget> sortedByTargetKey(final List<DeployExecutionTarget> targets) {
        return targets.stream()
                .sorted((left, right) -> Long.compare(
                        left.target().deployTargetKey(), right.target().deployTargetKey()))
                .toList();
    }

    private static List<String> sorted(final Set<String> resourceKeys) {
        return List.copyOf(new TreeSet<>(resourceKeys));
    }

    /** 폐쇄로만 끌려온 설비에 적용할 공유 상태입니다. */
    private record ClosureState(Long modelVersionKey, Long businessJarKey) {
    }
}
