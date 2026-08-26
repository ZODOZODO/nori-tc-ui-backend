package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiExecutorLease;
import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
import com.nori.tc.ui.core.coordination.model.UiOperationPhase;
import com.nori.tc.ui.core.coordination.model.UiOperationState;
import com.nori.tc.ui.core.coordination.model.UiOperationView;
import com.nori.tc.ui.core.coordination.port.UiCoordinationMembershipPort;
import com.nori.tc.ui.core.coordination.port.UiExecutorLeasePort;
import com.nori.tc.ui.core.coordination.port.UiFinalizedRevisionProvider;
import com.nori.tc.ui.core.coordination.port.UiOperationApplyDriver;
import com.nori.tc.ui.core.coordination.port.UiOperationCommandPublisher;
import com.nori.tc.ui.core.coordination.port.UiOperationDbFinalizationPort;
import com.nori.tc.ui.core.coordination.port.UiOperationLedgerPort;
import com.nori.tc.ui.core.coordination.port.UiResourceHeadPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * operation 상태 기계를 전진시키는 executor 입니다(§7.2 / §13.4 / §13.7 / §13.8).
 *
 * <p>모든 Pod 가 미종결 operation 을 발견할 수 있지만 <b>lease 를 얻은 한 session 만</b> 전이를
 * 수행합니다. executor Pod 가 죽으면 lease 만료 후 다른 Pod 가 원장과 DB 를 다시 읽어 마지막으로
 * 증명된 phase 부터 이어갑니다 — 로컬 완료 목록은 신뢰하지 않습니다.</p>
 *
 * <p>관찰 deadline 은 여기서 terminal 을 만들지 않습니다(§13.3). 지연은 조회 응답에서
 * {@code RECONCILING} 으로 표시될 뿐이고, 실제 결과는 늦게 도착한 proof 로 수렴합니다.</p>
 */
public final class UiOperationExecutor {

    private static final Logger log = LoggerFactory.getLogger(UiOperationExecutor.class);

    private static final int MAX_STEPS_PER_PASS = 8;

    /** 공통 원장이 "제외하면 participant 가 남지 않는다" 를 알리는 코드(participant_exclude.lua). */
    private static final String LEDGER_CODE_LAST_PARTICIPANT = "LAST_PARTICIPANT";

    /**
     * 보상으로 되돌릴 수 있는 수동 복구 사유입니다(§13.8).
     *
     * <p>두 사유 모두 DB 확정 <b>이전</b> 단계(APPLYING)의 실패라 되돌릴 DB 변경이 없고, 런타임만
     * 이전 revision 으로 되돌리면 됩니다. {@code DB_STATE_UNEXPECTED} 는 DB 의 실제 상태를 확정하지
     * 못한 경우이므로 여기 넣지 않습니다 — 모르는 상태를 자동으로 덮어쓰면 안 됩니다.</p>
     */
    private static final Set<String> COMPENSABLE_REPAIR_REASONS =
            Set.of("LAST_PARTICIPANT_EXPIRED", "POST_COMMIT_PARTICIPANT_REJECT", "APPLY_FAILED");

    /** 순서 있는 적용(deploy step)이 실패했을 때의 수동 복구 사유입니다(§13.6). */
    private static final String REPAIR_REASON_APPLY_FAILED = "APPLY_FAILED";

    private final UiCoordinationSession session;
    private final UiCoordinationProperties properties;
    private final UiOperationLedgerPort ledgerPort;
    private final UiResourceHeadPort resourceHeadPort;
    private final UiExecutorLeasePort leasePort;
    private final UiCoordinationMembershipPort membershipPort;
    private final UiOperationCommandPublisher commandPublisher;
    private final UiOperationDbFinalizationPort dbFinalizationPort;
    private final UiFinalizedRevisionProvider finalizedRevisionProvider;
    private final UiOperationManifestReader manifestReader;
    private final UiCompensationIssuer compensationIssuer;
    private final UiOperationApplyDriver applyDriver;
    private final UiCoordinationMetrics metrics;

    /**
     * executor 를 만듭니다.
     *
     * @param session 이 Pod 의 session
     * @param properties 조정 설정
     * @param ledgerPort operation ledger 포트
     * @param resourceHeadPort 리소스 장벽 포트
     * @param leasePort executor lease 포트
     * @param membershipPort membership 포트
     * @param commandPublisher 단계별 명령 발행 포트
     * @param dbFinalizationPort DB finalization 포트
     * @param finalizedRevisionProvider 마지막 finalized revision 기록/조회 포트
     * @param manifestReader manifest 해석기
     * @param compensationIssuer 보상 operation 발급기(§13.8)
     * @param applyDriver 순서 있는 적용 발행 전략(§13.6, 없으면 {@link UiOperationApplyDriver#none()})
     * @param metrics 조정 metric
     */
    public UiOperationExecutor(
            final UiCoordinationSession session,
            final UiCoordinationProperties properties,
            final UiOperationLedgerPort ledgerPort,
            final UiResourceHeadPort resourceHeadPort,
            final UiExecutorLeasePort leasePort,
            final UiCoordinationMembershipPort membershipPort,
            final UiOperationCommandPublisher commandPublisher,
            final UiOperationDbFinalizationPort dbFinalizationPort,
            final UiFinalizedRevisionProvider finalizedRevisionProvider,
            final UiOperationManifestReader manifestReader,
            final UiCompensationIssuer compensationIssuer,
            final UiOperationApplyDriver applyDriver,
            final UiCoordinationMetrics metrics
    ) {
        this.session = Objects.requireNonNull(session, "session is null");
        this.properties = Objects.requireNonNull(properties, "properties is null");
        this.ledgerPort = Objects.requireNonNull(ledgerPort, "ledgerPort is null");
        this.resourceHeadPort = Objects.requireNonNull(resourceHeadPort, "resourceHeadPort is null");
        this.leasePort = Objects.requireNonNull(leasePort, "leasePort is null");
        this.membershipPort = Objects.requireNonNull(membershipPort, "membershipPort is null");
        this.commandPublisher = Objects.requireNonNull(commandPublisher, "commandPublisher is null");
        this.dbFinalizationPort = Objects.requireNonNull(dbFinalizationPort, "dbFinalizationPort is null");
        this.finalizedRevisionProvider =
                Objects.requireNonNull(finalizedRevisionProvider, "finalizedRevisionProvider is null");
        this.manifestReader = Objects.requireNonNull(manifestReader, "manifestReader is null");
        this.compensationIssuer = Objects.requireNonNull(compensationIssuer, "compensationIssuer is null");
        this.applyDriver = Objects.requireNonNull(applyDriver, "applyDriver is null");
        this.metrics = Objects.requireNonNull(metrics, "metrics is null");
    }

    /**
     * 한 operation 을 가능한 만큼 전진시킵니다.
     *
     * @param operationId 대상 operation
     * @param nowEpochMs 현재 시각
     * @return 이 호출에서 수행한 전이 수(0 이면 진전 없음)
     */
    public int advance(final String operationId, final long nowEpochMs) {
        Objects.requireNonNull(operationId, "operationId is null");
        if (!session.active()) {
            // FENCED/JOINING session 은 확정 권한이 없다 — 여기서 전이하면 소유권 없는 Pod 가 결과를 만든다.
            return 0;
        }

        final Optional<UiExecutorLease> acquired = leasePort.tryAcquire(
                operationId, session.sessionId(), properties.getExecutorLeaseTtlMs(), nowEpochMs);
        if (acquired.isEmpty()) {
            return 0;
        }

        final UiExecutorLease lease = acquired.get();
        int steps = 0;
        try {
            while (steps < MAX_STEPS_PER_PASS) {
                final Optional<UiOperationView> loaded = ledgerPort.readOperation(operationId);
                if (loaded.isEmpty() || loaded.get().terminal()) {
                    break;
                }
                if (!advanceOnce(loaded.get(), lease, nowEpochMs)) {
                    break;
                }
                steps++;
            }
        } finally {
            leasePort.release(lease, nowEpochMs);
        }
        if (steps > 0) {
            metrics.recordOperationSteps(steps);
        }
        return steps;
    }

    private boolean advanceOnce(
            final UiOperationView operation,
            final UiExecutorLease lease,
            final long nowEpochMs
    ) {
        final UiOperationManifestView manifest = loadManifest(operation.operationId());
        return switch (operation.phase()) {
            case ACCEPTED -> startPrepare(operation, manifest, nowEpochMs);
            case PREPARING -> tryCloseBarrier(operation, manifest, nowEpochMs);
            case QUIESCING -> promotePrepared(operation, nowEpochMs);
            case PREPARED -> commit(operation, manifest, nowEpochMs);
            case COMMITTED -> startApply(operation, manifest, nowEpochMs);
            case APPLYING -> checkApplied(operation, manifest, nowEpochMs);
            case FINALIZING -> finalizeAndOpen(operation, manifest, lease, nowEpochMs);
            default -> false;
        };
    }

    private UiOperationManifestView loadManifest(final String operationId) {
        return manifestReader.read(ledgerPort.readManifest(operationId)
                .orElseThrow(() -> new IllegalStateException(
                        "operation manifest 가 없습니다 — 원장이 손상되었거나 다른 epoch 의 기록입니다. operationId="
                                + operationId)));
    }

    private boolean startPrepare(
            final UiOperationView operation,
            final UiOperationManifestView manifest,
            final long nowEpochMs
    ) {
        final List<String> participants = computeParticipants(manifest);
        if (participants.isEmpty()) {
            log.warn("UI_OPERATION_NO_PARTICIPANT. operationId={} — 처리 가능한 session 이 없어 대기합니다.",
                    operation.operationId());
            return false;
        }

        final String frozen = ledgerPort.freezeParticipants(
                operation.operationId(), participants, false, nowEpochMs);
        if (!"OK".equals(frozen) && !"ALREADY".equals(frozen)) {
            log.warn("UI_OPERATION_FREEZE_REJECTED. operationId={}, code={}", operation.operationId(), frozen);
            return false;
        }

        final String transitioned = ledgerPort.transition(
                operation.operationId(),
                EnumSet.of(UiOperationPhase.ACCEPTED),
                UiOperationPhase.PREPARING,
                null, null, null, null, null, null, nowEpochMs);
        if (!"OK".equals(transitioned)) {
            return false;
        }

        publishPrepare(operation, manifest, nowEpochMs);
        log.info("UI_OPERATION_PREPARING. operationId={}, participants={}, desiredRevision={}",
                operation.operationId(), participants, operation.desiredRevision());
        return true;
    }

    private void publishPrepare(
            final UiOperationView operation,
            final UiOperationManifestView manifest,
            final long nowEpochMs
    ) {
        commandPublisher.publishPrepare(
                operation.operationId(),
                manifest,
                requireRevision(operation),
                operation.manifestSha256(),
                deadlineAt(nowEpochMs));
    }

    private boolean tryCloseBarrier(
            final UiOperationView operation,
            final UiOperationManifestView manifest,
            final long nowEpochMs
    ) {
        final String code = ledgerPort.closeBarrier(operation.operationId(), headKeys(manifest), nowEpochMs);
        if ("OK".equals(code)) {
            log.info("UI_OPERATION_BARRIER_CLOSED. operationId={}", operation.operationId());
            return true;
        }
        if ("MISSING_PREPARE".equals(code)) {
            // 죽은 participant 는 커밋 전이므로 살아 있는 집합으로 다시 고정할 수 있다(§9.2).
            return refreezeLiveParticipants(operation, manifest, nowEpochMs);
        }
        log.warn("UI_OPERATION_BARRIER_CLOSE_REJECTED. operationId={}, code={}", operation.operationId(), code);
        return false;
    }

    private boolean refreezeLiveParticipants(
            final UiOperationView operation,
            final UiOperationManifestView manifest,
            final long nowEpochMs
    ) {
        final List<String> live = computeParticipants(manifest);
        if (live.isEmpty() || live.equals(operation.participants())) {
            return false;
        }
        final boolean anyExpired = operation.participants().stream().anyMatch(this::expiredParticipant);
        if (!anyExpired) {
            return false;
        }
        final String code = ledgerPort.freezeParticipants(operation.operationId(), live, true, nowEpochMs);
        if ("OK".equals(code)) {
            log.warn("UI_OPERATION_PARTICIPANTS_REFROZEN. operationId={}, before={}, after={}",
                    operation.operationId(), operation.participants(), live);
            metrics.incrementParticipantRefreeze();
            publishPrepare(operation, manifest, nowEpochMs);
            return true;
        }
        log.warn("UI_OPERATION_REFREEZE_REJECTED. operationId={}, code={}", operation.operationId(), code);
        return false;
    }

    private boolean promotePrepared(final UiOperationView operation, final long nowEpochMs) {
        final String code = ledgerPort.transition(
                operation.operationId(),
                EnumSet.of(UiOperationPhase.QUIESCING),
                UiOperationPhase.PREPARED,
                null, null, null, null, null, null, nowEpochMs);
        return "OK".equals(code);
    }

    private boolean commit(
            final UiOperationView operation,
            final UiOperationManifestView manifest,
            final long nowEpochMs
    ) {
        final List<String> keys = headKeys(manifest);
        final List<Long> baseRevisions = new ArrayList<>();
        for (final String resourceKey : manifest.resourceKeys()) {
            baseRevisions.add(manifest.expectedBaseRevisions().get(resourceKey));
        }
        final String code = ledgerPort.commitHead(operation.operationId(), keys, baseRevisions, nowEpochMs);
        if ("OK".equals(code)) {
            log.info("UI_OPERATION_COMMITTED. operationId={}, desiredRevision={}",
                    operation.operationId(), operation.desiredRevision());
            return true;
        }
        log.warn("UI_OPERATION_COMMIT_REJECTED. operationId={}, code={}", operation.operationId(), code);
        return false;
    }

    private boolean startApply(
            final UiOperationView operation,
            final UiOperationManifestView manifest,
            final long nowEpochMs
    ) {
        final String code = ledgerPort.transition(
                operation.operationId(),
                EnumSet.of(UiOperationPhase.COMMITTED),
                UiOperationPhase.APPLYING,
                null, null, null, null, null, null, nowEpochMs);
        if (!"OK".equals(code)) {
            return false;
        }
        publishApply(operation, manifest, nowEpochMs);
        return true;
    }

    private boolean checkApplied(
            final UiOperationView operation,
            final UiOperationManifestView manifest,
            final long nowEpochMs
    ) {
        /*
         * 순서가 있는 적용은 명령을 전부 내보내기 전에 proof 를 요구하면 안 된다(§13.6). driver 가
         * 아직 보낼 step 을 들고 있으면 이번 패스는 그 발행만 하고 끝낸다 — proof 판정은 다음 패스다.
         */
        final UiOperationApplyDriver.Result driven = driveApply(operation, manifest, nowEpochMs);
        switch (driven.progress()) {
            case ADVANCED -> {
                return true;
            }
            case WAITING -> {
                return false;
            }
            case FAILED -> {
                markRepairRequired(operation, REPAIR_REASON_APPLY_FAILED,
                        driven.errorCode() + ": " + driven.errorMessage(), nowEpochMs);
                return true;
            }
            default -> {
                // NOT_MINE / EXHAUSTED — 아래에서 평소대로 proof 를 판정한다.
            }
        }

        final int missing = ledgerPort.checkApplied(operation.operationId(), true, nowEpochMs);
        if (missing == 0) {
            log.info("UI_OPERATION_ALL_APPLIED. operationId={}", operation.operationId());
            return true;
        }
        if (missing < 0) {
            return false;
        }
        return excludeExpiredParticipants(operation, manifest, nowEpochMs);
    }

    /**
     * {@code APPLYING} 단계의 명령을 발행합니다.
     *
     * <p>순서가 있는 적용(deploy)은 driver 가 대신 발행합니다. driver 가 없거나 이 operation 을 맡지
     * 않으면 manifest 하나를 그대로 내보내는 기본 발행으로 돌아갑니다.</p>
     */
    private void publishApply(
            final UiOperationView operation,
            final UiOperationManifestView manifest,
            final long nowEpochMs
    ) {
        if (driveApply(operation, manifest, nowEpochMs).progress()
                != UiOperationApplyDriver.Progress.NOT_MINE) {
            return;
        }
        commandPublisher.publishApply(
                operation.operationId(),
                manifest,
                requireRevision(operation),
                operation.manifestSha256(),
                deadlineAt(nowEpochMs));
    }

    private UiOperationApplyDriver.Result driveApply(
            final UiOperationView operation,
            final UiOperationManifestView manifest,
            final long nowEpochMs
    ) {
        return applyDriver.driveApply(
                operation.operationId(),
                manifest,
                requireRevision(operation),
                operation.manifestSha256(),
                deadlineAt(nowEpochMs),
                nowEpochMs);
    }

    /**
     * 커밋 후 죽은 participant 를 고정 집합에서 제외합니다(§9.2 두 번째 규칙 / Plan §8 B18).
     *
     * <p>이 경로를 호출하지 않으면 커밋 직후 Pod 가 죽은 operation 의 barrier 는 <b>영구히</b> 열리지
     * 않고 그 설비의 처리가 멈춘 채로 남습니다.</p>
     */
    private boolean excludeExpiredParticipants(
            final UiOperationView operation,
            final UiOperationManifestView manifest,
            final long nowEpochMs
    ) {
        boolean excluded = false;
        for (final String participantId : operation.participants()) {
            if (!expiredParticipant(participantId)) {
                continue;
            }
            final String serviceName = serviceNameOf(participantId);
            final String code = ledgerPort.excludeExpiredParticipant(
                    operation.operationId(), serviceName, participantId, nowEpochMs);
            if ("OK".equals(code)) {
                log.warn("UI_OPERATION_PARTICIPANT_EXCLUDED. operationId={}, participantId={}",
                        operation.operationId(), participantId);
                metrics.incrementParticipantExclusion();
                excluded = true;
            } else if (LEDGER_CODE_LAST_PARTICIPANT.equals(code)) {
                /*
                 * 고정 집합의 마지막 participant 가 죽었다 — 제외하면 committed revision 의 적용을 증명할
                 * session 이 남지 않으므로 공통 원장이 제외를 거부한다(participant_exclude.lua 는 이 판정을
                 * executor 소관으로 명시한다). 계속 재시도하면 operation 이 terminal 에 도달하지 못한 채
                 * 그 설비의 barrier 가 영구히 닫혀 있고 운영자에게는 아무 신호도 가지 않는다.
                 */
                markRepairRequired(operation, "LAST_PARTICIPANT_EXPIRED",
                        "커밋 후 마지막 participant 가 만료되어 적용을 증명할 session 이 없습니다.", nowEpochMs);
                return true;
            } else {
                log.info("UI_OPERATION_PARTICIPANT_EXCLUDE_REJECTED. operationId={}, participantId={}, code={}",
                        operation.operationId(), participantId, code);
            }
        }
        if (!excluded) {
            // 살아 있는 participant 를 기다리는 정상 대기다. APPLY 명령을 다시 밀어 지연을 줄인다.
            publishApply(operation, manifest, nowEpochMs);
        }
        return excluded;
    }

    private boolean finalizeAndOpen(
            final UiOperationView operation,
            final UiOperationManifestView manifest,
            final UiExecutorLease lease,
            final long nowEpochMs
    ) {
        final long desiredRevision = requireRevision(operation);
        final UiOperationDbFinalizationPort.Outcome outcome =
                dbFinalizationPort.finalizeToTarget(operation.operationId(), manifest, desiredRevision);

        if (outcome == UiOperationDbFinalizationPort.Outcome.UNEXPECTED_STATE) {
            markRepairRequired(operation, "DB_STATE_UNEXPECTED",
                    "DB 가 예상 이전/목표 상태 어느 쪽도 아니어서 덮어쓰지 않았습니다.", nowEpochMs);
            return true;
        }

        recordFinalizedRevisions(manifest, desiredRevision);

        final String finalized = ledgerPort.recordDbFinalized(
                operation.operationId(), lease.sessionId(), lease.epoch(), desiredRevision, nowEpochMs);
        if (!"OK".equals(finalized)) {
            log.warn("UI_OPERATION_DB_FINALIZE_PROOF_REJECTED. operationId={}, code={}",
                    operation.operationId(), finalized);
            return false;
        }

        final String opened = ledgerPort.openBarrierAndPass(
                operation.operationId(), headKeys(manifest), nowEpochMs);
        if ("OK".equals(opened)) {
            log.info("UI_OPERATION_PASS. operationId={}, desiredRevision={}",
                    operation.operationId(), desiredRevision);
            metrics.incrementOperationPass();
            return true;
        }
        log.warn("UI_OPERATION_BARRIER_OPEN_REJECTED. operationId={}, code={}", operation.operationId(), opened);
        return false;
    }

    private void recordFinalizedRevisions(final UiOperationManifestView manifest, final long desiredRevision) {
        for (final String resourceKey : manifest.resourceKeys()) {
            final String eqpId = UiResourceKeyPlanner.eqpIdOf(resourceKey);
            if (!finalizedRevisionProvider.recordFinalizedRevision(eqpId, desiredRevision)) {
                /*
                 * 컬럼이 아직 없으면(P5 Flyway V7 선행) 기록이 남지 않는다. 그 사실을 조용히 넘기면
                 * 원장 후퇴 검증(§11.11/§12.3)이 무력한 채로 운영에 나간다.
                 */
                metrics.incrementFinalizedRevisionNotRecorded();
                log.warn("UI_FINALIZED_REVISION_NOT_RECORDED. eqpId={}, revision={} — "
                                + "마지막 finalized revision 컬럼이 없어 원장 후퇴 검증이 동작하지 않습니다.",
                        eqpId, desiredRevision);
            }
        }
    }

    /**
     * commit 전 실패를 확정합니다(§7.2 — candidate abort 후 기존 open revision 으로 재개).
     *
     * @param operationId 대상 operation
     * @param errorCode 실패 코드
     * @param errorMsg 실패 메시지
     * @param nowEpochMs 현재 시각
     * @return abort 되었으면 true
     */
    public boolean abortBeforeCommit(
            final String operationId,
            final String errorCode,
            final String errorMsg,
            final long nowEpochMs
    ) {
        final Optional<UiOperationView> loaded = ledgerPort.readOperation(operationId);
        if (loaded.isEmpty() || loaded.get().terminal()) {
            return false;
        }
        final UiOperationView operation = loaded.get();
        if (committedOrLater(operation.phase())) {
            log.warn("UI_OPERATION_ABORT_AFTER_COMMIT_REJECTED. operationId={}, phase={} — "
                            + "commit 이후에는 pointer 를 되돌리지 않고 보상 경로로 처리합니다.",
                    operationId, operation.phase());
            return false;
        }
        final UiOperationManifestView manifest = loadManifest(operationId);
        final String code = ledgerPort.abort(operationId, headKeys(manifest), errorCode, errorMsg, nowEpochMs);
        if ("OK".equals(code)) {
            log.info("UI_OPERATION_ABORTED. operationId={}, errorCode={}", operationId, errorCode);
            metrics.incrementOperationFail();
            return true;
        }
        log.warn("UI_OPERATION_ABORT_REJECTED. operationId={}, code={}", operationId, code);
        return false;
    }

    /**
     * 커밋 후 실패를 확정하고, 되돌릴 수 있는 사유면 보상 operation 을 발급합니다(§7.2/§13.8).
     *
     * <p>순서가 계약입니다 — 먼저 {@code REPAIR_REQUIRED} 로 확정한 뒤에 보상을 발급합니다. 공통
     * 원장은 그 상태의 operation 이 닫아 둔 리소스에 대해서만 claim 인계를 허용하고, 발급이 실패하면
     * 이미 확정된 수동 복구 신호가 그대로 남습니다.</p>
     *
     * @param operation 대상 operation
     * @param repairReason 수동 복구 사유
     * @param errorMsg 오류 메시지
     * @param nowEpochMs 현재 시각
     */
    public void markRepairRequired(
            final UiOperationView operation,
            final String repairReason,
            final String errorMsg,
            final long nowEpochMs
    ) {
        final String code = ledgerPort.transition(
                operation.operationId(),
                EnumSet.of(UiOperationPhase.COMMITTED, UiOperationPhase.APPLYING,
                        UiOperationPhase.FINALIZING, UiOperationPhase.COMPENSATING),
                UiOperationPhase.COMPENSATING,
                UiOperationState.REPAIR_REQUIRED,
                null, null, "REPAIR_REQUIRED", errorMsg, repairReason, nowEpochMs);
        log.error("UI_OPERATION_REPAIR_REQUIRED. operationId={}, repairReason={}, code={}",
                operation.operationId(), repairReason, code);
        metrics.incrementOperationRepairRequired();

        if (!"OK".equals(code) || !COMPENSABLE_REPAIR_REASONS.contains(repairReason)) {
            return;
        }
        final String compensationId = compensationIssuer.issueFor(operation, nowEpochMs);
        if (compensationId != null) {
            metrics.incrementCompensationIssued();
        }
    }

    /**
     * manifest 의 participant 집합을 산출합니다(§11.3 / Plan §8 B17).
     *
     * <p>반드시 상태·protocolVersion 을 필터한 조회를 씁니다 — 무필터 조회를 쓰면 JOINING/FENCED/구 Pod 가
     * 고정 집합에 들어가 §11.3 과 §22.1 이 함께 깨집니다.</p>
     */
    private List<String> computeParticipants(final UiOperationManifestView manifest) {
        final Set<String> participants = new LinkedHashSet<>(membershipPort.activeSyncedSessionIds(
                properties.getBusinessServiceName(),
                properties.getProtocolVersion(),
                properties.getParticipantScanLimit()));
        if (manifest.requiresGatewayParticipant()) {
            participants.addAll(membershipPort.activeSyncedSessionIds(
                    properties.getGatewayServiceName(),
                    properties.getProtocolVersion(),
                    properties.getParticipantScanLimit()));
        }
        return List.copyOf(participants);
    }

    private boolean expiredParticipant(final String participantId) {
        return membershipPort.expired(serviceNameOf(participantId), participantId);
    }

    private static String serviceNameOf(final String sessionId) {
        final int separator = sessionId.indexOf(':');
        if (separator < 0) {
            throw new IllegalArgumentException("malformed sessionId: " + sessionId);
        }
        return sessionId.substring(0, separator);
    }

    private List<String> headKeys(final UiOperationManifestView manifest) {
        return manifest.resourceKeys().stream().map(resourceHeadPort::headKeyOf).toList();
    }

    private String deadlineAt(final long nowEpochMs) {
        return Instant.ofEpochMilli(nowEpochMs + properties.getObservationDeadlineMs()).toString();
    }

    private static long requireRevision(final UiOperationView operation) {
        final Long revision = operation.desiredRevision();
        if (revision == null) {
            throw new IllegalStateException(
                    "desired revision 이 발급되지 않은 operation 입니다. operationId=" + operation.operationId());
        }
        return revision;
    }

    private static boolean committedOrLater(final UiOperationPhase phase) {
        return switch (phase) {
            case COMMITTED, APPLYING, FINALIZING, BARRIER_OPEN, COMPENSATING -> true;
            default -> false;
        };
    }
}
