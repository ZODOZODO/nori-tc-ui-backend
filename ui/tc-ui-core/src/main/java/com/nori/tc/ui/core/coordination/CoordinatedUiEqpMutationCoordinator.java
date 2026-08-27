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
import com.nori.tc.ui.core.service.EqpManagementCommandValidator;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.eqp.UiEqp;
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
    private final EqpManagementCommandValidator commandValidator;
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
     * @param commandValidator 입력·모델 바인딩 검증기
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
            final EqpManagementCommandValidator commandValidator,
            final ObjectMapper objectMapper
    ) {
        this.commandValidator = Objects.requireNonNull(commandValidator, "commandValidator is null");
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
        /*
         * §13.2 — validation 은 원장 operation 을 만들기 <b>전에</b> 끝낸다. 접수한 뒤에 발견되는 입력
         * 오류는 리소스 claim 을 잡은 operation 을 실패시키고 보상까지 끌고 가지만, 여기서 걸리면
         * 400 으로 끝난다. 즉시 적용 경로({@code EqpDirectScenarioService})와 같은 검증기를 같은
         * 순서로 호출해 구성에 따라 통과 기준이 달라지지 않게 한다.
         */
        commandValidator.validateCreateCommand(command);
        commandValidator.validateModelBinding(
                command.interfaceType(), command.isDev(), command.modelVersionKey());
        commandValidator.validateCreateTargetAbsent(
                eqpManageQueryPort.findManageSnapshotByEqpId(command.eqpId()), command.eqpId());

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
                /*
                 * §16.1 — 생성의 "이전 상태" 는 tombstone(존재하지 않음)이다. 비워 두면 보상 manifest 를
                 * 만들 수 없어(previousDescriptors 부재) 커밋 후 실패가 임시 행을 남긴 채 수동 복구로만
                 * 끝난다. tombstone 을 남기면 보상이 그 행을 지우고 삭제 tombstone 을 커밋한다.
                 */
                descriptorFactory.forDelete(command.eqpId(), null),
                Set.of(command.modelVersionKey()),
                command.businessJarRef(),
                UiOperationRequest.DB_COMMAND_CREATE,
                objectMapper.writeValueAsString(command),
                idempotencyKey,
                requiresGatewayParticipant(target));
    }

    @Override
    public UiOperationAcceptance update(
            final String eqpId,
            final EqpManagementCommand.Update command,
            final String idempotencyKey
    ) {
        commandValidator.validateEqpId(eqpId);
        final EqpManagementSnapshot before = requireSnapshot(eqpId);
        /*
         * §13.2 — 즉시 적용 경로와 같은 검증을 접수 전에 수행한다. 다만 즉시 적용 경로의
         * "STARTED 설비의 routePartition 변경 거부" 는 여기로 옮기지 않는다 — 조정 프로토콜은 그
         * 이동을 old owner release / new owner bind 로 나눠 안전하게 수행하는 것이 §12.5 의 내용이고,
         * 거부하면 그 절차를 쓸 수 없다.
         */
        commandValidator.validateUpdateCommand(command, before);
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
                idempotencyKey,
                requiresGatewayParticipant(target) || changesGatewayRuntime(before, command, modelChanged));
    }

    @Override
    public UiOperationAcceptance delete(final String eqpId, final String idempotencyKey) {
        commandValidator.validateEqpId(eqpId);
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
                idempotencyKey,
                requiresGatewayParticipant(tombstone));
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
            final String idempotencyKey,
            final boolean requiresGatewayParticipant
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
                requiresGatewayParticipant,
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

    /**
     * 수정 명령이 Gateway 런타임이 읽는 값을 바꾸는지 판정합니다(§12.4).
     *
     * <p>descriptor 만 보면 판정할 수 없습니다 — descriptor 는 <b>공유 상태</b>(모델/JAR/route)만 담고
     * 접속 파라미터(IP/포트/연결모드/socketType/HSMS deviceId/PLC 튜닝)는 담지 않습니다. 그 값들은
     * Gateway 의 {@code EquipmentContextProfile} 로 그대로 들어가므로, 바뀌었는데 Gateway 를 참여자로
     * 두지 않으면 Gateway 는 옛 접속 파라미터로 계속 통신하고 DB/Business 만 새 값으로 확정됩니다 —
     * 이 SPEC 이 없애려는 무음 불일치가 정확히 그것입니다.</p>
     *
     * <p>반대로 model 파라미터만 바뀌는 non-PLC 변경은 여기 걸리지 않습니다. Gateway 가 모델을 읽는
     * 곳은 PLC 디코드 스펙뿐이므로({@code PlcEquipmentSpecReloader} / {@code PlcSpecBootstrap} 의
     * {@code info.modelKey()}) SECS/SOCKET 설비의 모델 변경은 Gateway 에 할 일을 만들지 않습니다.</p>
     *
     * @param before 변경 전 스냅샷
     * @param command 수정 명령
     * @param modelChanged modelVersionKey 변경 여부
     * @return Gateway 참여가 필요하면 true
     */
    private static boolean changesGatewayRuntime(
            final EqpManagementSnapshot before,
            final EqpManagementCommand.Update command,
            final boolean modelChanged
    ) {
        final UiEqp eqp = before.eqp();
        if (!Objects.equals(eqp.eqpIp(), command.eqpIp())
                || !Objects.equals(eqp.eqpPort(), command.eqpPort())
                || !Objects.equals(eqp.commMode(), command.commMode())) {
            return true;
        }
        if (command.socketSettings() != null && before.socket() != null
                && !Objects.equals(
                        before.socket().socketProtocolType(), command.socketSettings().socketProtocolType())) {
            return true;
        }
        if (command.hsmsSettings() != null && before.hsms() != null
                && !Objects.equals(before.hsms().deviceId(), command.hsmsSettings().deviceId())) {
            return true;
        }
        if (command.plcSettings() != null && before.plc() != null && changesPlcTuning(before, command.plcSettings())) {
            return true;
        }
        // PLC 디코드 스펙은 모델에서 오므로 모델이 바뀌면 Gateway 가 스펙을 다시 적재해야 한다.
        return modelChanged && eqp.commInterface() == UiProtocolType.PLC;
    }

    private static boolean changesPlcTuning(
            final EqpManagementSnapshot before,
            final EqpManagementCommand.PlcSettings settings
    ) {
        return !Objects.equals(before.plc().plcProtocol(), settings.plcProtocol())
                || !Objects.equals(before.plc().transport(), settings.transport())
                || !Objects.equals(before.plc().unitId(), settings.unitId())
                || !Objects.equals(before.plc().pollTimeoutMs(), settings.pollTimeoutMs())
                || !Objects.equals(before.plc().reconnectMaxRetries(), settings.reconnectMaxRetries());
    }

    private EqpManagementSnapshot requireSnapshot(final String eqpId) {
        return eqpManageQueryPort.findManageSnapshotByEqpId(eqpId)
                .orElseThrow(() -> new UiNotFoundException("설비를 찾을 수 없습니다. eqpId=" + eqpId));
    }

    private static String interfaceTypeOf(final String rawInterfaceType) {
        return rawInterfaceType == null || rawInterfaceType.isBlank() ? "SOCKET" : rawInterfaceType;
    }
}
