package com.nori.tc.ui.core.service;

import com.nori.tc.common.runtime.support.ExecutorShutdownResult;
import com.nori.tc.common.runtime.support.ExecutorShutdownSupport;
import com.nori.tc.common.runtime.support.RuntimeLogSummary;
import com.nori.tc.ui.core.deploy.DeployCreateResult;
import com.nori.tc.ui.core.deploy.DeployPlanCommand;
import com.nori.tc.ui.core.deploy.DeployPlanningResult;
import com.nori.tc.ui.core.deploy.DeployResultMessage;
import com.nori.tc.ui.core.deploy.DeployResultSeverity;
import com.nori.tc.ui.core.deploy.DeployStatus;
import com.nori.tc.ui.core.deploy.DeploySummarySnapshot;
import com.nori.tc.ui.core.deploy.DeploySummaryView;
import com.nori.tc.ui.core.deploy.DeployTargetCounts;
import com.nori.tc.ui.core.deploy.DeployTargetPage;
import com.nori.tc.ui.core.deploy.PersistedDeploy;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiNotFoundException;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

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
    private final ExecutorService targetExecutor;
    private final ConcurrentHashMap<String, ReentrantLock> eqpExecutionLocks;
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
        this.targetExecutor = Executors.newFixedThreadPool(
                Math.max(1, validatedDeployProperties.targetConcurrency()),
                commandAssembler.namedThreadFactory(TARGET_EXECUTOR_THREAD_PREFIX, targetExecutorThreadSequence)
        );
        this.eqpExecutionLocks = new ConcurrentHashMap<>();
        log.info("DeployService target executor initialized. {}",
                RuntimeLogSummary.kv(
                        "targetConcurrency", validatedDeployProperties.targetConcurrency(),
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
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        final List<PersistedDeployTarget> executableTargets = persistedDeploy.targets().stream()
                .filter(target -> !target.planDraft().noChange())
                .toList();
        if (executableTargets.isEmpty()) {
            deployStorePort.updateDeployStatus(
                    persistedDeploy.deployKey(),
                    DeployStatus.COMPLETED,
                    now,
                    now,
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
                now,
                null,
                null,
                null
        );
        executableTargets.forEach(target -> targetExecutor.submit(() -> deployExecutor.executeTarget(
                persistedDeploy.deployKey(),
                planningResult.eventType(),
                target,
                normalizedActor,
                eqpExecutionLocks
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
                        "waitMs", result.waitMs(),
                        "activeEqpLocks", eqpExecutionLocks.size()
                ));
    }
}
