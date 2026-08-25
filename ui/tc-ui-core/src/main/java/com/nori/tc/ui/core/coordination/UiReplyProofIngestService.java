package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationPhase;
import com.nori.tc.ui.core.coordination.model.UiOperationView;
import com.nori.tc.ui.core.coordination.port.UiOperationLedgerPort;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.model.UiCommandReplyProof;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * Kafka reply 의 coordination proof 를 원장에 기록합니다(§13.5).
 *
 * <p>규율 셋을 지킵니다.</p>
 * <ol>
 *   <li>Redis 에 기록하지 못하면 <b>infrastructure failure</b> 로 분류해 ACK 를 막습니다. ACK 하면 그
 *       proof 는 영구히 사라지고 operation 은 영원히 열린 채 남습니다.</li>
 *   <li>reply 의 operationId/revision/digest/session 이 현재 operation 과 <b>모두</b> 일치할 때만
 *       proof 를 기록합니다. 늦게 도착한 stale 응답이 새 상태를 덮지 못하는 근거가 이 조건입니다.</li>
 *   <li>local registry 나 Pub/Sub 없이도 진행됩니다 — 이 경로만으로 operation 이 전진해야 합니다.</li>
 * </ol>
 */
public final class UiReplyProofIngestService {

    private static final Logger log = LoggerFactory.getLogger(UiReplyProofIngestService.class);

    private final UiOperationLedgerPort ledgerPort;
    private final UiOperationExecutor executor;
    private final UiCoordinationMetrics metrics;

    /**
     * ingest 서비스를 만듭니다.
     *
     * @param ledgerPort operation ledger 포트
     * @param executor operation executor
     * @param metrics 조정 metric
     */
    public UiReplyProofIngestService(
            final UiOperationLedgerPort ledgerPort,
            final UiOperationExecutor executor,
            final UiCoordinationMetrics metrics
    ) {
        this.ledgerPort = Objects.requireNonNull(ledgerPort, "ledgerPort is null");
        this.executor = Objects.requireNonNull(executor, "executor is null");
        this.metrics = Objects.requireNonNull(metrics, "metrics is null");
    }

    /**
     * reply proof 를 기록합니다.
     *
     * @param reply 수신한 reply
     * @param nowEpochMs 현재 시각
     * @return proof 를 기록했거나 이미 같은 proof 가 있으면 true
     * @throws UiCoordinationUnavailableException 원장 기록 불가(ACK 금지)
     */
    public boolean ingest(final UiCommandReply reply, final long nowEpochMs) {
        Objects.requireNonNull(reply, "reply is null");
        final UiCommandReplyProof proof = reply.proof();
        if (proof == null) {
            return false;
        }

        final Optional<UiOperationView> loaded = ledgerPort.readOperation(proof.operationId());
        if (loaded.isEmpty()) {
            log.warn("UI_REPLY_PROOF_UNKNOWN_OPERATION. operationId={}, traceId={}, source={}",
                    proof.operationId(), reply.traceId(), reply.source());
            metrics.incrementReplyProofRejected();
            return false;
        }

        final UiOperationView operation = loaded.get();
        if (!matchesCurrentOperation(operation, proof)) {
            log.warn("UI_REPLY_PROOF_MISMATCH. operationId={}, replyRevision={}, operationRevision={}, "
                            + "replyDigest={}, operationDigest={}",
                    proof.operationId(), proof.desiredRevision(), operation.desiredRevision(),
                    proof.payloadSha256(), operation.manifestSha256());
            metrics.incrementReplyProofRejected();
            return false;
        }

        if (UiCommandReplyProof.STATE_STALE_FENCED.equals(proof.commandState())) {
            // 소유권을 잃은 session 의 응답은 성공으로도 실패로도 쓰지 않는다 — 감사 기록만 남긴다.
            log.warn("UI_REPLY_PROOF_STALE_FENCED. operationId={}, sessionId={}",
                    proof.operationId(), proof.serviceSessionId());
            metrics.incrementReplyProofRejected();
            return false;
        }

        if (proof.claimsRejected()) {
            return rejectBeforeCommit(operation, reply, proof, nowEpochMs);
        }

        final boolean applied = proof.claimsApplied() && appliedPhase(operation.phase());
        final String code = ledgerPort.ackProof(
                proof.operationId(),
                proof.serviceSessionId(),
                applied,
                proof.desiredRevision(),
                proof.payloadSha256(),
                null,
                proof.ownerEpoch(),
                "{}",
                nowEpochMs);

        if ("OK".equals(code)) {
            log.info("UI_REPLY_PROOF_RECORDED. operationId={}, sessionId={}, kind={}, revision={}",
                    proof.operationId(), proof.serviceSessionId(), applied ? "applied" : "prepared",
                    proof.desiredRevision());
            return true;
        }

        /*
         * PHASE_MISMATCH 는 "아직 그 proof 를 받을 단계가 아님" 이다. Gateway 는 하나의 명령을 원자적으로
         * 준비·적용하고 APPLIED 로 답하므로, commit 전에 온 APPLIED 응답은 prepared proof 로만 기록된다.
         * applied proof 는 executor 가 APPLY 단계에서 같은 명령을 재발행할 때 result ledger replay 로
         * 다시 받아 기록한다(§12.6) — 여기서 예외를 던져 ACK 를 막으면 그 재발행이 도착하기 전까지
         * partition 이 멈춘다.
         */
        if ("PHASE_MISMATCH".equals(code) || "ALREADY_TERMINAL".equals(code)) {
            log.info("UI_REPLY_PROOF_DEFERRED. operationId={}, sessionId={}, code={}, phase={}",
                    proof.operationId(), proof.serviceSessionId(), code, operation.phase());
            return false;
        }

        log.warn("UI_REPLY_PROOF_REJECTED. operationId={}, sessionId={}, code={}",
                proof.operationId(), proof.serviceSessionId(), code);
        metrics.incrementReplyProofRejected();
        return false;
    }

    private boolean rejectBeforeCommit(
            final UiOperationView operation,
            final UiCommandReply reply,
            final UiCommandReplyProof proof,
            final long nowEpochMs
    ) {
        final String errorCode = reply.errorCode() == null ? "PARTICIPANT_REJECTED" : reply.errorCode();
        final String errorMsg = reply.errorMsg() == null
                ? "participant 가 commit 전 검증에서 거부했습니다."
                : reply.errorMsg();
        if (executor.abortBeforeCommit(operation.operationId(), errorCode, errorMsg, nowEpochMs)) {
            return true;
        }
        /*
         * commit 이후의 거부는 pointer 를 되돌리지 않는다(§7.2). 보상 없이 성공으로 넘길 수도 없으므로
         * 수동 복구 대상으로 확정하고 리소스 barrier 를 닫힌 채로 둔다.
         */
        executor.markRepairRequired(operation, "POST_COMMIT_PARTICIPANT_REJECT", errorMsg, nowEpochMs);
        return true;
    }

    private static boolean matchesCurrentOperation(
            final UiOperationView operation,
            final UiCommandReplyProof proof
    ) {
        return Objects.equals(operation.desiredRevision(), proof.desiredRevision())
                && Objects.equals(operation.manifestSha256(), proof.payloadSha256());
    }

    private static boolean appliedPhase(final UiOperationPhase phase) {
        return phase == UiOperationPhase.COMMITTED || phase == UiOperationPhase.APPLYING;
    }
}
