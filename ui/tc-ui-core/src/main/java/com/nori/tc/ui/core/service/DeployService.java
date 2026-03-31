package com.nori.tc.ui.core.service;

import com.nori.tc.common.runtime.support.ExecutorShutdownResult;
import com.nori.tc.common.runtime.support.ExecutorShutdownSupport;
import com.nori.tc.common.runtime.support.RuntimeExecutorFactory;
import com.nori.tc.common.runtime.support.RuntimeLogSummary;
import com.nori.tc.ui.core.deploy.DeployCreateResult;
import com.nori.tc.ui.core.deploy.DeployActionResult;
import com.nori.tc.ui.core.deploy.DeployDeleteResult;
import com.nori.tc.ui.core.deploy.DeployPlanCommand;
import com.nori.tc.ui.core.deploy.DeployPlanningResult;
import com.nori.tc.ui.core.deploy.DeployResultMessage;
import com.nori.tc.ui.core.deploy.DeployResultSeverity;
import com.nori.tc.ui.core.deploy.DeployStatus;
import com.nori.tc.ui.core.deploy.DeployHistoryView;
import com.nori.tc.ui.core.deploy.DeploySummarySnapshot;
import com.nori.tc.ui.core.deploy.DeploySummaryView;
import com.nori.tc.ui.core.deploy.DeployTargetCounts;
import com.nori.tc.ui.core.deploy.DeployTargetPage;
import com.nori.tc.ui.core.deploy.PersistedDeploy;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.port.db.DeployStorePort;
import com.nori.tc.ui.core.properties.UiDeployProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
            final DeployTraceRecorder deployTraceRecorder,
            final DeployCommandAssembler commandAssembler,
            final DeployExecutor deployExecutor
    ) {
        final UiDeployProperties validatedDeployProperties =
                Objects.requireNonNull(deployProperties, "deployProperties is null");
        this.deployPlanner = Objects.requireNonNull(deployPlanner, "deployPlanner is null");
        this.deployStorePort = Objects.requireNonNull(deployStorePort, "deployStorePort is null");
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
     * deploy를 생성하고 background execution을 시작합니다.
     *
     * @param command deploy 입력
     * @param actor 요청 사용자
     * @return 생성 결과
     */
    public DeployCreateResult createDeploy(
            final DeployPlanCommand command,
            final String actor
    ) {
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
        if (executableTargets.isEmpty()) {
            deployStorePort.updateDeployStatus(
                    persistedDeploy.deployKey(),
                    DeployStatus.COMPLETED,
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
                DeployStatus.RUNNING,
                null,
                null
        );
        executableTargets.forEach(target -> targetExecutor.submit(() -> deployExecutor.executeTarget(
                persistedDeploy.deployKey(),
                planningResult.eventType(),
                target,
                normalizedActor
        )));
        return new DeployCreateResult(
                persistedDeploy.deployKey(),
                DeployStatus.RUNNING,
                "DEPLOY_ACCEPTED",
                new DeployResultMessage(
                        "작업이 접수되었습니다.",
                        "설비 " + persistedDeploy.targets().size() + "대에 대한 작업이 등록되었습니다.",
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

        return new DeploySummaryView(
                snapshot.deployKey(),
                snapshot.deployName(),
                snapshot.eventType(),
                snapshot.deployStatus(),
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
     * terminal deploy 이력을 삭제합니다.
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
        if (!commandAssembler.isTerminal(summary.deployStatus())) {
            throw new UiConflictException("종료된 Deploy 이력만 삭제할 수 있습니다. deployKey=" + deployKey);
        }

        final String normalizedActor = commandAssembler.normalizeActor(actor);
        final boolean deleted = deployStorePort.deleteDeploy(deployKey);
        if (!deleted) {
            throw new UiNotFoundException("삭제할 Deploy를 찾을 수 없습니다. deployKey=" + deployKey);
        }

        log.info("Deploy 이력 삭제 완료. {}",
                RuntimeLogSummary.kv(
                        "deployKey", deployKey,
                        "actor", normalizedActor
                ));

        return new DeployDeleteResult(
                deployKey,
                true,
                "DEPLOY_DELETED",
                new DeployResultMessage(
                        "Deploy 이력이 삭제되었습니다.",
                        "선택한 Deploy 이력을 목록에서 제거했습니다.",
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

    private boolean isCancelable(final DeployStatus deployStatus) {
        return switch (deployStatus) {
            case PLANNING, READY, RUNNING -> true;
            case COMPLETED, PARTIAL_FAILED, FAILED, CANCELED -> false;
        };
    }
}
