package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
import com.nori.tc.ui.core.coordination.model.UiOperationRequest;
import com.nori.tc.ui.core.coordination.port.UiOperationApplyDriver;
import com.nori.tc.ui.core.deploy.DeployExecutionPlan;
import com.nori.tc.ui.core.deploy.DeployExecutionTarget;
import com.nori.tc.ui.core.deploy.DeployResolvedStepPlan;
import com.nori.tc.ui.core.deploy.DeployStepStatus;
import com.nori.tc.ui.core.deploy.DeployTargetFinalizationCommand;
import com.nori.tc.ui.core.deploy.DeployTraceCompletion;
import com.nori.tc.ui.core.model.UiCommandCoordination;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.port.db.DeployStorePort;
import com.nori.tc.ui.core.registry.DeployTraceRedisLookupResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * deploy operation 의 {@code APPLYING} 단계를 순서대로, 그러나 <b>블로킹 없이</b> 전진시킵니다(§13.6).
 *
 * <p>기존 실행기는 step 마다 로컬 {@code CompletableFuture} 를 최대 60s 기다렸습니다. 그 대기를
 * executor lease(기본 15s) 안에서 하면 lease 가 만료되어 다른 Pod 가 같은 operation 을 중복
 * 전진시키므로, 여기서는 한 호출에 <b>한 step 만</b> 다룹니다.</p>
 *
 * <p>한 패스가 하는 일:</p>
 * <ol>
 *   <li>진행 중인 step 이 있으면 그 traceId 의 분산 저장소 상태를 조회한다</li>
 *   <li>아직이면 {@code WAITING} — 다음 reconcile 주기가 다시 본다</li>
 *   <li>성공이면 그 step 을 완료로 기록하고 다음 step 을 발행한다</li>
 *   <li>보낼 step 이 없으면 {@code EXHAUSTED} — proof 수집은 executor 가 한다</li>
 * </ol>
 *
 * <p>응답 자체는 이미 Redis 에 있으므로({@link DeployTraceRegistry} 가 등록/기록을 분산 저장소에
 * 합니다) 실행 Pod 가 죽어도 인계 Pod 가 같은 지점에서 이어갑니다 — 로컬 future 를 쓰지 않는 것이
 * 이 클래스의 요점입니다.</p>
 */
@Service
public class DeployApplyDriver implements UiOperationApplyDriver {

    private static final Logger log = LoggerFactory.getLogger(DeployApplyDriver.class);

    private final DeployStorePort deployStorePort;
    private final DeployCommandAssembler commandAssembler;
    private final DeployTraceRecorder deployTraceRecorder;
    private final ObjectMapper objectMapper;

    /**
     * driver 를 만듭니다.
     *
     * @param deployStorePort deploy 영속 상태 포트
     * @param commandAssembler step 명령 조립기
     * @param deployTraceRecorder step 응답 추적기
     * @param objectMapper DB 확정 명령 파서
     */
    public DeployApplyDriver(
            final DeployStorePort deployStorePort,
            final DeployCommandAssembler commandAssembler,
            final DeployTraceRecorder deployTraceRecorder,
            final ObjectMapper objectMapper
    ) {
        this.deployStorePort = Objects.requireNonNull(deployStorePort, "deployStorePort is null");
        this.commandAssembler = Objects.requireNonNull(commandAssembler, "commandAssembler is null");
        this.deployTraceRecorder = Objects.requireNonNull(deployTraceRecorder, "deployTraceRecorder is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
    }

    @Override
    public Result driveApply(
            final String operationId,
            final UiOperationManifestView manifest,
            final long desiredRevision,
            final String manifestSha256,
            final String deadlineAt,
            final long nowEpochMs
    ) {
        if (!UiOperationRequest.DB_COMMAND_DEPLOY_TARGET.equals(manifest.dbCommandKind())) {
            return Result.of(Progress.NOT_MINE);
        }

        final DeployTargetFinalizationCommand command =
                objectMapper.readValue(manifest.dbCommandJson(), DeployTargetFinalizationCommand.class);
        final Optional<DeployExecutionPlan> loaded =
                deployStorePort.findDeployExecutionPlan(command.deployKey());
        if (loaded.isEmpty()) {
            return Result.failed("DEPLOY_PLAN_NOT_FOUND",
                    "실행할 Deploy target 정보를 찾을 수 없습니다. deployKey=" + command.deployKey());
        }
        final DeployExecutionPlan plan = loaded.get();

        boolean advanced = false;
        boolean waiting = false;
        for (final DeployExecutionTarget target : plan.targets()) {
            if (!command.deployTargetKeys().contains(target.target().deployTargetKey())) {
                continue;
            }
            final Result result = driveTarget(
                    operationId, plan.eventType(), target, command, desiredRevision,
                    manifestSha256, manifest, deadlineAt);
            switch (result.progress()) {
                case FAILED -> {
                    return result;
                }
                case ADVANCED -> advanced = true;
                case WAITING -> waiting = true;
                default -> {
                    // 이 target 은 보낼 step 이 남지 않았다.
                }
            }
        }

        if (advanced) {
            return Result.of(Progress.ADVANCED);
        }
        return Result.of(waiting ? Progress.WAITING : Progress.EXHAUSTED);
    }

    private Result driveTarget(
            final String operationId,
            final UiCommandEventType eventType,
            final DeployExecutionTarget target,
            final DeployTargetFinalizationCommand command,
            final long desiredRevision,
            final String manifestSha256,
            final UiOperationManifestView manifest,
            final String deadlineAt
    ) {
        final List<DeployResolvedStepPlan> steps = target.target().planDraft().resolvedSteps();

        if (target.currentStepStatus() == DeployStepStatus.WAITING_REPLY && target.currentStepOrder() != null) {
            return awaitCurrentStep(operationId, target, steps);
        }

        final int nextIndex = nextStepIndex(target, steps);
        if (nextIndex >= steps.size()) {
            return Result.of(Progress.EXHAUSTED);
        }
        publishStep(operationId, eventType, target, command, steps.get(nextIndex),
                desiredRevision, manifestSha256, manifest, deadlineAt);
        return Result.of(Progress.ADVANCED);
    }

    /**
     * 발행해 둔 step 의 응답을 조회합니다.
     *
     * <p>조회는 로컬 future 가 아니라 분산 저장소를 봅니다 — 이 step 을 발행한 Pod 가 아니어도 같은
     * 결론에 도달해야 합니다.</p>
     */
    private Result awaitCurrentStep(
            final String operationId,
            final DeployExecutionTarget target,
            final List<DeployResolvedStepPlan> steps
    ) {
        final String traceId = stepTraceId(
                operationId, target.target().deployTargetKey(), target.currentStepCode());
        final DeployTraceRedisLookupResult lookup = deployTraceRecorder.completionOf(traceId);
        return switch (lookup.status()) {
            case PENDING -> Result.of(Progress.WAITING);
            case EXPIRED -> expiredCurrentStep(operationId, target, traceId);
            case INFRA_ERROR -> Result.failed("DEPLOY_TRACE_UNAVAILABLE",
                    "step 응답 저장소를 조회하지 못했습니다. " + lookup.errorMessage());
            case COMPLETED -> completeCurrentStep(operationId, target, steps, lookup.completion());
        };
    }

    /**
     * 등록이 사라진 step 을 판정합니다.
     *
     * <p>확정 근거는 <b>영속화된 step deadline</b> 이지 Redis TTL 이 아닙니다 — TTL 만으로 확정하면
     * 원장 유실이 곧바로 거짓 timeout 이 됩니다. deadline 전이면 계속 대기하고, deadline 을 넘긴
     * 뒤에만 실패로 확정합니다. 확정 코드는 비조정 경로({@code DeployExecutor})와 같은
     * {@code STEP_TIMEOUT} 입니다 — 같은 사건을 두 경로가 다르게 부르면 안 됩니다.</p>
     */
    private Result expiredCurrentStep(
            final String operationId,
            final DeployExecutionTarget target,
            final String traceId
    ) {
        final OffsetDateTime deadline = target.currentStepTimeoutAt();
        if (deadline == null || OffsetDateTime.now(ZoneOffset.UTC).isBefore(deadline)) {
            return Result.of(Progress.WAITING);
        }

        log.error("DEPLOY_APPLY_STEP_TIMEOUT. operationId={}, deployTargetKey={}, step={}, "
                        + "traceId={}, stepTimeoutAt={}",
                operationId, target.target().deployTargetKey(), target.currentStepCode(),
                traceId, deadline);
        deployTraceRecorder.cancel(traceId);
        return Result.failed("STEP_TIMEOUT", "step 응답 대기 시간이 초과되었습니다.");
    }

    private Result completeCurrentStep(
            final String operationId,
            final DeployExecutionTarget target,
            final List<DeployResolvedStepPlan> steps,
            final DeployTraceCompletion completion
    ) {
        final DeployResolvedStepPlan current = stepOfOrder(steps, target.currentStepOrder());
        if (!completion.success()) {
            log.error("DEPLOY_APPLY_STEP_FAILED. operationId={}, deployTargetKey={}, step={}, errorCode={}",
                    operationId, target.target().deployTargetKey(), target.currentStepCode(),
                    completion.firstErrorCode());
            return Result.failed(
                    completion.firstErrorCode() == null ? "STEP_FAILED" : completion.firstErrorCode(),
                    completion.firstErrorMessage() == null
                            ? "step 응답이 실패로 도착했습니다." : completion.firstErrorMessage());
        }

        /*
         * 완료 표시는 "여기까지 끝났다" 체크포인트일 뿐이고 target 의 최종 성공이 아니다(§13.6).
         * target COMPLETED 는 operation 이 barrier 를 열 때 DB 확정 단계가 기록한다.
         */
        deployStorePort.markTargetReadyForResume(
                target.target().deployTargetKey(),
                current == null ? null : current.order(),
                current == null ? null : current.code(),
                OffsetDateTime.now(ZoneOffset.UTC));
        return Result.of(Progress.ADVANCED);
    }

    private void publishStep(
            final String operationId,
            final UiCommandEventType eventType,
            final DeployExecutionTarget target,
            final DeployTargetFinalizationCommand command,
            final DeployResolvedStepPlan step,
            final long desiredRevision,
            final String manifestSha256,
            final UiOperationManifestView manifest,
            final String deadlineAt
    ) {
        if (step.rollbackEnabled()) {
            commandAssembler.saveCompensationSnapshot(
                    target.target(), step, commandAssembler.captureTargetPreState(target.target()).orElse(null));
        }

        final String traceId = stepTraceId(operationId, target.target().deployTargetKey(), step.code());
        final OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        deployStorePort.markTargetWaitingReply(
                target.target().deployTargetKey(),
                step.order(),
                step.code(),
                1,
                traceId,
                startedAt,
                startedAt.plus(Duration.ofMillis(step.timeoutMs())));

        deployTraceRecorder.register(traceId, commandAssembler.expectedSources(step), step.timeoutMs());
        commandAssembler.publishStepCommand(
                command.deployKey(),
                eventType,
                target.target(),
                step,
                traceId,
                command.actor(),
                coordinationOf(operationId, manifest, desiredRevision, manifestSha256, deadlineAt));
        log.info("DEPLOY_APPLY_STEP_PUBLISHED. operationId={}, deployTargetKey={}, step={}, revision={}",
                operationId, target.target().deployTargetKey(), step.code(), desiredRevision);
    }

    /**
     * step 의 traceId 를 결정적으로 만듭니다.
     *
     * <p>인계 Pod 가 같은 step 을 다시 발행해도 같은 traceId 라, 수신 측 result ledger 가 최초 결과를
     * 재현합니다(§12.6). 저장하지 않고 도출하므로 인계 시점에 조회할 상태가 하나 줄어듭니다.</p>
     *
     * @param operationId 대상 operation
     * @param deployTargetKey target 식별자
     * @param stepCode step 코드
     * @return traceId
     */
    public static String stepTraceId(
            final String operationId,
            final long deployTargetKey,
            final String stepCode
    ) {
        return operationId + ":APPLY:" + deployTargetKey + ":" + stepCode;
    }

    /**
     * step 명령에 실을 coordination metadata 를 만듭니다.
     *
     * <p>이 값이 비면 조정 구성의 Business 가 명령을 {@code COORDINATION_METADATA_REQUIRED} 로
     * 거부합니다(§22.1).</p>
     */
    private static UiCommandCoordination coordinationOf(
            final String operationId,
            final UiOperationManifestView manifest,
            final long desiredRevision,
            final String manifestSha256,
            final String deadlineAt
    ) {
        final String primaryResourceKey =
                manifest.resourceKeys().isEmpty() ? "" : manifest.resourceKeys().get(0);
        return new UiCommandCoordination(
                operationId,
                manifest.executionId(),
                UiCommandCoordination.PHASE_APPLY,
                manifest.expectedBaseRevisions().get(primaryResourceKey),
                desiredRevision,
                manifestSha256,
                manifest.resourceKeys(),
                deadlineAt);
    }

    /**
     * 다음에 발행할 step 의 인덱스를 구합니다.
     *
     * <p>로컬 완료 목록을 신뢰하지 않고 영속 상태의 마지막 완료 step 부터 셉니다(§13.4).</p>
     */
    private static int nextStepIndex(
            final DeployExecutionTarget target,
            final List<DeployResolvedStepPlan> steps
    ) {
        if (target.currentStepOrder() == null || target.currentStepStatus() != DeployStepStatus.COMPLETED) {
            return 0;
        }
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).order() > target.currentStepOrder()) {
                return index;
            }
        }
        return steps.size();
    }

    private static DeployResolvedStepPlan stepOfOrder(
            final List<DeployResolvedStepPlan> steps,
            final Integer order
    ) {
        if (order == null) {
            return null;
        }
        return steps.stream().filter(step -> step.order() == order).findFirst().orElse(null);
    }
}
