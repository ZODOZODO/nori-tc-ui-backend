package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import com.nori.tc.common.runtime.support.ExecutorShutdownResult;
import com.nori.tc.common.runtime.support.ExecutorShutdownSupport;
import com.nori.tc.common.runtime.support.RuntimeExecutorFactory;
import com.nori.tc.common.runtime.support.RuntimeLogSummary;
import com.nori.tc.ui.core.deploy.DeployActiveEqpConflict;
import com.nori.tc.ui.core.deploy.DeployActionResult;
import com.nori.tc.ui.core.deploy.DeployCreateResult;
import com.nori.tc.ui.core.deploy.DeployDeleteResult;
import com.nori.tc.ui.core.deploy.DeployExecutionPlan;
import com.nori.tc.ui.core.deploy.DeployExecutionTarget;
import com.nori.tc.ui.core.deploy.DeployHistoryView;
import com.nori.tc.ui.core.deploy.DeployPlanCommand;
import com.nori.tc.ui.core.deploy.DeployPlanningResult;
import com.nori.tc.ui.core.deploy.DeployResultMessage;
import com.nori.tc.ui.core.deploy.DeployResultSeverity;
import com.nori.tc.ui.core.deploy.DeploySnapshotKind;
import com.nori.tc.ui.core.deploy.DeploySnapshotScope;
import com.nori.tc.ui.core.deploy.DeployStateOverview;
import com.nori.tc.ui.core.deploy.DeployStatus;
import com.nori.tc.ui.core.deploy.DeployStoredSnapshot;
import com.nori.tc.ui.core.deploy.DeploySummarySnapshot;
import com.nori.tc.ui.core.deploy.DeploySummaryView;
import com.nori.tc.ui.core.deploy.DeployTargetCounts;
import com.nori.tc.ui.core.deploy.DeployTargetPage;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.deploy.PersistedDeploy;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.exception.UiErrorCode;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.port.db.DeployStorePort;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import com.nori.tc.ui.core.properties.UiDeployProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * deploy 생성 / 실행 / polling 조회를 담당합니다.
 */
@Service
public class DeployService {

    private static final Logger log = LoggerFactory.getLogger(DeployService.class);

    private static final int MAX_TARGET_PAGE_SIZE = 200;
    private static final long TARGET_EXECUTOR_SHUTDOWN_WAIT_MS = 5_000L;
    private static final String TARGET_EXECUTOR_THREAD_PREFIX = "ui-deploy-target-";

    private final DeployPlanner deployPlanner;
    private final DeployStorePort deployStorePort;
    private final EqpCrudPort eqpCrudPort;
    private final DeployTraceRecorder deployTraceRecorder;
    private final DeployCommandAssembler commandAssembler;
    private final DeployExecutor deployExecutor;
    private final ThreadPoolExecutor targetExecutor;
    private final int targetConcurrency;
    private final AtomicInteger targetExecutorThreadSequence = new AtomicInteger(0);

    public DeployService(
            final UiDeployProperties deployProperties,
            final DeployPlanner deployPlanner,
            final DeployStorePort deployStorePort,
            final EqpCrudPort eqpCrudPort,
            final DeployTraceRecorder deployTraceRecorder,
            final DeployCommandAssembler commandAssembler,
            final DeployExecutor deployExecutor
    ) {
        final UiDeployProperties validatedDeployProperties =
                Objects.requireNonNull(deployProperties, "deployProperties is null");
        this.deployPlanner = Objects.requireNonNull(deployPlanner, "deployPlanner is null");
        this.deployStorePort = Objects.requireNonNull(deployStorePort, "deployStorePort is null");
        this.eqpCrudPort = Objects.requireNonNull(eqpCrudPort, "eqpCrudPort is null");
        this.deployTraceRecorder = Objects.requireNonNull(deployTraceRecorder, "deployTraceRecorder is null");
        this.commandAssembler = Objects.requireNonNull(commandAssembler, "commandAssembler is null");
        this.deployExecutor = Objects.requireNonNull(deployExecutor, "deployExecutor is null");
        this.targetConcurrency = Math.max(1, validatedDeployProperties.targetConcurrency());
        this.targetExecutor = (ThreadPoolExecutor) RuntimeExecutorFactory.defaultFactory().newFixedThreadPool(
                targetConcurrency,
                commandAssembler.namedThreadFactory(TARGET_EXECUTOR_THREAD_PREFIX, targetExecutorThreadSequence)
        );
        log.info("DeployService target executor initialized. {}",
                RuntimeLogSummary.kv(
                        "targetConcurrency", targetConcurrency,
                        "threadPrefix", TARGET_EXECUTOR_THREAD_PREFIX
                ));
    }

    /**
     * deploy를 생성하고 실행 대기 상태로 저장합니다.
     *
     * @param command deploy 입력
     * @param actor 요청 사용자
     * @return 생성 결과
     */
    public DeployCreateResult createDeploy(
            final DeployPlanCommand command,
            final String actor
    ) {
        rejectWhenActiveEqpConflict(resolveTargetEqpIds(command), null);
        final DeployPlanningResult planningResult = deployPlanner.plan(command);
        final String normalizedActor = commandAssembler.normalizeActor(actor);
        final PersistedDeploy persistedDeploy = deployStorePort.createDeploy(
                command,
                planningResult,
                normalizedActor
        );
        final List<PersistedDeployTarget> executableTargets = persistedDeploy.targets().stream()
                .filter(target -> !target.planDraft().noChange())
                .toList();
        executableTargets.forEach(commandAssembler::captureTargetPreState);
        if (executableTargets.isEmpty()) {
            deployStorePort.updateDeployStatus(
                    persistedDeploy.deployKey(),
                    DeployStatus.COMPLETED,
                    false,
                    null,
                    null
            );
            return new DeployCreateResult(
                    persistedDeploy.deployKey(),
                    DeployStatus.COMPLETED,
                    "DEPLOY_COMPLETED",
                    commandAssembler.completedMessage(new DeployTargetCounts(
                            persistedDeploy.targets().size(),
                            0,
                            persistedDeploy.targets().size(),
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0
                    ))
            );
        }

        deployStorePort.updateDeployStatus(
                persistedDeploy.deployKey(),
                DeployStatus.READY,
                false,
                null,
                null
        );
        return new DeployCreateResult(
                persistedDeploy.deployKey(),
                DeployStatus.READY,
                "DEPLOY_CREATED",
                new DeployResultMessage(
                        "Deploy가 생성되었습니다.",
                        "Deploy 탭에서 실행해 주세요.",
                        DeployResultSeverity.INFO
                )
        );
    }

    /**
     * deploy summary polling 결과를 조회합니다.
     *
     * @param deployKey deploy key
     * @return summary view
     */
    public DeploySummaryView getDeploySummary(final long deployKey) {
        final DeploySummarySnapshot snapshot = deployStorePort.findDeploySummary(deployKey)
                .orElseThrow(() -> new UiNotFoundException("deploy를 찾을 수 없습니다. deployKey=" + deployKey));

        final boolean resolvedTerminal = commandAssembler.isTerminal(snapshot.deployStatus());
        final String resultCode = commandAssembler.resolveResultCode(snapshot.deployStatus(), snapshot.targetSummary());
        final DeployResultMessage resultMessage = commandAssembler.resolveSummaryMessage(
                snapshot.deployStatus(),
                snapshot.targetSummary()
        );
        final DeployStateOverview asIsState = resolveAsIsState(snapshot.deployKey(), snapshot.targetSummary().total());

        return new DeploySummaryView(
                snapshot.deployKey(),
                snapshot.deployName(),
                snapshot.eventType(),
                snapshot.resolvedScenarioCode(),
                asIsState,
                snapshot.deployStatus(),
                snapshot.pauseRequested(),
                snapshot.executionPolicy(),
                snapshot.createdAt(),
                snapshot.startedAt(),
                snapshot.finishedAt(),
                snapshot.targetSummary(),
                resolvedTerminal,
                resolvedTerminal,
                resultCode,
                resultMessage
        );
    }

    private DeployStateOverview resolveAsIsState(final long deployKey, final int targetTotalCount) {
        if (targetTotalCount <= 0) {
            return null;
        }

        final DeployTargetPage targetPage = deployStorePort.findDeployTargets(
                deployKey,
                0,
                Math.max(targetTotalCount, 50),
                null,
                null
        );

        final LinkedHashSet<String> interfaceTypes = new LinkedHashSet<>();
        final LinkedHashSet<String> modelNames = new LinkedHashSet<>();
        final LinkedHashSet<String> modelVersions = new LinkedHashSet<>();
        final LinkedHashSet<String> businessJarNames = new LinkedHashSet<>();
        final LinkedHashSet<String> eqpParamVersions = new LinkedHashSet<>();

        for (final var target : targetPage.targets()) {
            final DeployStoredSnapshot snapshot = deployStorePort.findLatestTargetSnapshot(
                    target.deployTargetKey(),
                    DeploySnapshotScope.TARGET_PRE_STATE,
                    null,
                    DeploySnapshotKind.TARGET_FULL_STATE
            ).orElse(null);

            if (snapshot != null && snapshot.snapshotData() != null) {
                final JsonNode snapshotData = snapshot.snapshotData();
                addIfPresent(interfaceTypes, snapshotData.path("interfaceType").asText(null));
                addIfPresent(modelNames, snapshotData.path("modelInfo").path("modelName").asText(null));
                addIfPresent(modelVersions, snapshotData.path("modelInfo").path("modelVersion").asText(null));
                addIfPresent(businessJarNames, snapshotData.path("businessJar").path("jarFileName").asText(null));
                addIfPresent(eqpParamVersions, snapshotData.path("eqpParams").path("version").asText(null));
                continue;
            }
            addIfPresent(interfaceTypes, resolveInterfaceType(target.eqpId()));
            addAsIsFromLiveSnapshot(
                    target.eqpId(),
                    modelNames,
                    modelVersions,
                    businessJarNames,
                    eqpParamVersions
            );
        }

        final String interfaceType = summarizeValues(interfaceTypes);
        final String modelName = summarizeValues(modelNames);
        final String modelVersion = summarizeValues(modelVersions);
        final String businessJarName = summarizeValues(businessJarNames);
        final String eqpParamVersion = summarizeValues(eqpParamVersions);

        if (interfaceType == null
                && modelName == null
                && modelVersion == null
                && businessJarName == null
                && eqpParamVersion == null) {
            return null;
        }

        return new DeployStateOverview(
                interfaceType,
                modelName,
                modelVersion,
                businessJarName,
                eqpParamVersion
        );
    }

    private void addIfPresent(final LinkedHashSet<String> values, final String value) {
        final String normalized = commandAssembler.normalizeOptionalText(value);
        if (normalized != null) {
            values.add(normalized);
        }
    }

    private String resolveInterfaceType(final String eqpId) {
        return eqpCrudPort.findSnapshotByEqpId(eqpId)
                .map(EqpManagementSnapshot::eqp)
                .map(eqp -> eqp.commInterface() == null ? null : eqp.commInterface().name())
                .orElse(null);
    }

    private void addAsIsFromLiveSnapshot(
            final String eqpId,
            final LinkedHashSet<String> modelNames,
            final LinkedHashSet<String> modelVersions,
            final LinkedHashSet<String> businessJarNames,
            final LinkedHashSet<String> eqpParamVersions
    ) {
        final EqpManagementSnapshot snapshot = eqpCrudPort.findSnapshotByEqpId(eqpId).orElse(null);
        if (snapshot == null) {
            return;
        }
        if (snapshot.model() != null) {
            addIfPresent(modelNames, snapshot.model().modelName());
            addIfPresent(modelVersions, snapshot.model().modelVersion());
        }
        if (snapshot.businessJar() != null) {
            addIfPresent(businessJarNames, snapshot.businessJar().jarFileName());
        }
        addIfPresent(eqpParamVersions, snapshot.eqp().appliedParamVersion());
    }

    private String summarizeValues(final LinkedHashSet<String> values) {
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() == 1) {
            return values.iterator().next();
        }
        return "대상별 상이: " + String.join(", ", values);
    }

    /**
     * deploy 목록을 조회합니다.
     *
     * @param modelName model name 또는 deploy name 필터
     * @param requestedBy 요청 사용자 필터
     * @param fromCreatedAt 생성 시각 시작 inclusive
     * @param toCreatedAtExclusive 생성 시각 종료 exclusive
     * @param terminalOnly 완료 상태만 조회할지 여부
     * @param pageRequest 페이지 요청
     * @return deploy history 페이지
     */
    public PagedResponse<DeployHistoryView> getDeployHistory(
            final String modelName,
            final String requestedBy,
            final OffsetDateTime fromCreatedAt,
            final OffsetDateTime toCreatedAtExclusive,
            final boolean terminalOnly,
            final UiPageRequest pageRequest
    ) {
        return deployStorePort.findDeployHistory(
                commandAssembler.normalizeOptionalText(modelName),
                commandAssembler.normalizeOptionalText(requestedBy),
                fromCreatedAt,
                toCreatedAtExclusive,
                terminalOnly,
                pageRequest
        );
    }

    /**
     * deploy target 페이지를 조회합니다.
     *
     * @param deployKey deploy key
     * @param page page index
     * @param size page size
     * @param statusFilter 상태 필터
     * @param eqpIdFilter eqpId 필터
     * @return target page
     */
    public DeployTargetPage getDeployTargets(
            final long deployKey,
            final int page,
            final int size,
            final String statusFilter,
            final String eqpIdFilter
    ) {
        deployStorePort.findDeploySummary(deployKey)
                .orElseThrow(() -> new UiNotFoundException("deploy를 찾을 수 없습니다. deployKey=" + deployKey));

        if (page < 0) {
            throw new UiBadRequestException("page는 0 이상이어야 합니다.");
        }
        if (size <= 0) {
            throw new UiBadRequestException("size는 1 이상이어야 합니다.");
        }
        if (size > MAX_TARGET_PAGE_SIZE) {
            throw new UiBadRequestException("size는 " + MAX_TARGET_PAGE_SIZE + " 이하여야 합니다.");
        }

        final var targetStatus = commandAssembler.parseTargetStatus(statusFilter);
        return deployStorePort.findDeployTargets(
                deployKey,
                page,
                size,
                targetStatus,
                commandAssembler.normalizeOptionalText(eqpIdFilter)
        );
    }

    /**
     * tc.ui.commands reply를 deploy trace에 반영합니다.
     *
     * @param reply 수신 reply
     * @return deploy trace 처리 여부
     */
    public boolean handleReply(final UiCommandReply reply) {
        return deployTraceRecorder.handle(reply);
    }

    /**
     * 진행 중 deploy 취소를 요청합니다.
     *
     * @param deployKey deploy key
     * @param actor 요청 사용자
     * @return 취소 결과
     */
    public DeployActionResult cancelDeploy(
            final long deployKey,
            final String actor
    ) {
        final DeploySummarySnapshot summary = requireDeploySummary(deployKey);
        if (!isCancelable(summary.deployStatus())) {
            throw new UiConflictException("진행 중인 Deploy만 취소할 수 있습니다. deployKey=" + deployKey);
        }

        final String normalizedActor = commandAssembler.normalizeActor(actor);
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        final List<String> activeTraceIds = deployStorePort.findActiveTraceIds(deployKey);

        deployStorePort.markTargetsCanceled(
                deployKey,
                now,
                "DEPLOY_CANCELED",
                "사용자 취소 요청으로 작업이 중단되었습니다."
        );
        deployStorePort.updateDeployStatus(
                deployKey,
                DeployStatus.CANCELED,
                false,
                "DEPLOY_CANCELED",
                "사용자 취소 요청으로 작업이 중단되었습니다."
        );
        activeTraceIds.forEach(deployTraceRecorder::cancel);

        log.info("Deploy 취소 완료. {}",
                RuntimeLogSummary.kv(
                        "deployKey", deployKey,
                        "actor", normalizedActor,
                        "canceledTraceCount", activeTraceIds.size()
                ));

        return new DeployActionResult(
                deployKey,
                DeployStatus.CANCELED,
                false,
                "DEPLOY_CANCELED",
                new DeployResultMessage(
                        "Deploy가 취소되었습니다.",
                        "진행 중이던 Deploy를 취소했습니다.",
                        DeployResultSeverity.INFO
                )
        );
    }

    /**
     * terminal deploy 를 기준으로 새 deploy 를 다시 생성합니다.
     *
     * @param deployKey 원본 deploy key
     * @param actor 요청 사용자
     * @return 새 deploy 생성 결과
     */
    public DeployCreateResult redeploy(
            final long deployKey,
            final String actor
    ) {
        final DeploySummarySnapshot summary = requireDeploySummary(deployKey);
        if (!commandAssembler.isTerminal(summary.deployStatus())) {
            throw new UiConflictException("종료된 Deploy만 다시 실행할 수 있습니다. deployKey=" + deployKey);
        }

        final DeployPlanCommand command = deployStorePort.findRedeployCommand(deployKey)
                .orElseThrow(() -> new UiNotFoundException("재실행할 Deploy 요청 정보를 찾을 수 없습니다. deployKey=" + deployKey));

        return createDeploy(command, actor);
    }

    /**
     * READY deploy 를 실행 상태로 전이하고 target dispatch 를 시작합니다.
     *
     * @param deployKey deploy key
     * @param actor 요청 사용자
     * @return 실행 시작 결과
     */
    public DeployActionResult startDeploy(
            final long deployKey,
            final String actor
    ) {
        final DeploySummarySnapshot summary = requireDeploySummary(deployKey);
        if (summary.deployStatus() != DeployStatus.READY) {
            throw new UiConflictException("READY 상태의 Deploy만 실행할 수 있습니다. deployKey=" + deployKey);
        }
        if (summary.pauseRequested()) {
            throw new UiConflictException("hold 요청이 남아 있는 Deploy는 실행할 수 없습니다. deployKey=" + deployKey);
        }

        final DeployExecutionPlan executionPlan = deployStorePort.findDeployExecutionPlan(deployKey)
                .orElseThrow(() -> new UiNotFoundException("실행할 Deploy target 정보를 찾을 수 없습니다. deployKey=" + deployKey));
        rejectWhenActiveEqpConflict(
                executionPlan.targets().stream()
                        .map(target -> target.target().planDraft().eqpId())
                        .toList(),
                deployKey
        );
        final List<DeployExecutionTarget> dispatchTargets = executionPlan.targets().stream()
                .filter(target -> target.targetStatus() == com.nori.tc.ui.core.deploy.DeployTargetStatus.PENDING)
                .filter(target -> !target.target().planDraft().noChange())
                .toList();

        deployStorePort.updateDeployStatus(
                deployKey,
                DeployStatus.RUNNING,
                false,
                null,
                null
        );
        dispatchTargets.forEach(target -> targetExecutor.submit(() -> deployExecutor.executeTarget(
                deployKey,
                executionPlan.eventType(),
                target,
                commandAssembler.normalizeActor(actor)
        )));

        return new DeployActionResult(
                deployKey,
                DeployStatus.RUNNING,
                false,
                "DEPLOY_STARTED",
                new DeployResultMessage(
                        "Deploy가 시작되었습니다.",
                        "선택한 Deploy 실행을 시작했습니다.",
                        DeployResultSeverity.INFO
                )
        );
    }

    /**
     * RUNNING deploy 에 safe hold 를 요청합니다.
     *
     * @param deployKey deploy key
     * @param actor 요청 사용자
     * @return hold 요청 결과
     */
    public DeployActionResult holdDeploy(
            final long deployKey,
            final String actor
    ) {
        final DeploySummarySnapshot summary = requireDeploySummary(deployKey);
        if (summary.deployStatus() != DeployStatus.RUNNING) {
            throw new UiConflictException("RUNNING 상태의 Deploy만 hold 할 수 있습니다. deployKey=" + deployKey);
        }
        if (summary.pauseRequested()) {
            throw new UiConflictException("이미 hold 요청이 접수된 Deploy 입니다. deployKey=" + deployKey);
        }

        final String normalizedActor = commandAssembler.normalizeActor(actor);
        deployStorePort.updatePauseRequested(deployKey, true);
        log.info("Deploy hold 요청 접수. {}",
                RuntimeLogSummary.kv(
                        "deployKey", deployKey,
                        "actor", normalizedActor
                ));

        return new DeployActionResult(
                deployKey,
                DeployStatus.RUNNING,
                true,
                "DEPLOY_HOLD_REQUESTED",
                new DeployResultMessage(
                        "Hold 요청이 접수되었습니다.",
                        "현재 step 완료 후 Deploy가 대기 상태로 전환됩니다.",
                        DeployResultSeverity.INFO
                )
        );
    }

    /**
     * 실행 전 또는 종료된 deploy 항목을 삭제합니다.
     *
     * @param deployKey deploy key
     * @param actor 요청 사용자
     * @return 삭제 결과
     */
    public DeployDeleteResult deleteDeploy(
            final long deployKey,
            final String actor
    ) {
        final DeploySummarySnapshot summary = requireDeploySummary(deployKey);
        if (!isDeletable(summary.deployStatus())) {
            throw new UiConflictException("실행 전 또는 종료된 Deploy만 삭제할 수 있습니다. deployKey=" + deployKey);
        }

        final String normalizedActor = commandAssembler.normalizeActor(actor);
        final boolean deleted = deployStorePort.deleteDeploy(deployKey);
        if (!deleted) {
            throw new UiNotFoundException("삭제할 Deploy를 찾을 수 없습니다. deployKey=" + deployKey);
        }

        log.info("Deploy 삭제 완료. {}",
                RuntimeLogSummary.kv(
                        "deployKey", deployKey,
                        "actor", normalizedActor
                ));

        return new DeployDeleteResult(
                deployKey,
                true,
                "DEPLOY_DELETED",
                new DeployResultMessage(
                        "Deploy가 삭제되었습니다.",
                        "선택한 Deploy 항목을 목록에서 제거했습니다.",
                        DeployResultSeverity.INFO
                )
        );
    }

    public int targetExecutorActiveCount() {
        return targetExecutor.getActiveCount();
    }

    public int targetExecutorQueueSize() {
        return targetExecutor.getQueue().size();
    }

    public int targetConcurrency() {
        return targetConcurrency;
    }

    @PreDestroy
    public void shutdown() {
        final ExecutorShutdownResult result = ExecutorShutdownSupport.shutdown(
                targetExecutor,
                TARGET_EXECUTOR_SHUTDOWN_WAIT_MS
        );
        log.info("DeployService target executor stopped. {}",
                RuntimeLogSummary.kv(
                        "terminated", result.terminated(),
                        "forced", result.forced(),
                        "interrupted", result.interrupted(),
                        "droppedTasks", result.droppedTaskCount(),
                        "waitMs", result.waitMs()
                ));
    }

    private DeploySummarySnapshot requireDeploySummary(final long deployKey) {
        return deployStorePort.findDeploySummary(deployKey)
                .orElseThrow(() -> new UiNotFoundException("deploy를 찾을 수 없습니다. deployKey=" + deployKey));
    }

    private List<String> resolveTargetEqpIds(final DeployPlanCommand command) {
        return command.targets().stream()
                .map(DeployPlanCommand.TargetRequest::eqpId)
                .toList();
    }

    private void rejectWhenActiveEqpConflict(
            final Collection<String> eqpIds,
            final Long excludedDeployKey
    ) {
        deployStorePort.findActiveEqpConflict(eqpIds, excludedDeployKey)
                .ifPresent(this::throwActiveEqpConflict);
    }

    private void throwActiveEqpConflict(final DeployActiveEqpConflict conflict) {
        throw new UiConflictException(
                UiErrorCode.DEPLOY_ACTIVE_EQP_CONFLICT,
                "이미 active Deploy에 포함된 설비입니다. eqpId=" + conflict.eqpId()
                        + ", activeDeployKey=" + conflict.deployKey()
                        + ", activeDeployStatus=" + conflict.deployStatus().name()
        );
    }

    private boolean isCancelable(final DeployStatus deployStatus) {
        return switch (deployStatus) {
            case PLANNING, READY, RUNNING -> true;
            case COMPLETED, PARTIAL_FAILED, FAILED, CANCELED -> false;
        };
    }

    private boolean isDeletable(final DeployStatus deployStatus) {
        return switch (deployStatus) {
            case PLANNING, READY, COMPLETED, PARTIAL_FAILED, FAILED, CANCELED -> true;
            case RUNNING -> false;
        };
    }
}
