package com.nori.tc.ui.core.coordination;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * §20.1 이 요구하는 UI Backend 조정 metric 입니다.
 *
 * <p>관측 전에도 series 가 존재해야 경보 규칙이 "값 없음" 과 "0" 을 구분할 수 있으므로 생성자에서
 * 전 계열을 등록합니다.</p>
 */
public final class UiCoordinationMetrics {

    private final Counter operationPass;
    private final Counter operationFail;
    private final Counter operationRepairRequired;
    private final Counter participantRefreeze;
    private final Counter participantExclusion;
    private final Counter idempotencyConflict;
    private final Counter replyProofRejected;
    private final Counter finalizedRevisionNotRecorded;
    private final Counter compensationIssued;
    private final Counter operationRolledBack;
    private final DistributionSummary operationSteps;
    private final AtomicInteger recoveryBacklog = new AtomicInteger();
    private final AtomicLong executorEpoch = new AtomicLong();

    /**
     * metric 을 등록합니다.
     *
     * @param meterRegistry Micrometer 레지스트리
     */
    public UiCoordinationMetrics(final MeterRegistry meterRegistry) {
        Objects.requireNonNull(meterRegistry, "meterRegistry is null");
        this.operationPass = Counter.builder("tc.ui.operation.terminal")
                .tag("result", "PASS").register(meterRegistry);
        this.operationFail = Counter.builder("tc.ui.operation.terminal")
                .tag("result", "FAIL").register(meterRegistry);
        this.operationRepairRequired = Counter.builder("tc.ui.operation.terminal")
                .tag("result", "REPAIR_REQUIRED").register(meterRegistry);
        this.participantRefreeze = Counter.builder("tc.ui.operation.participant.refreeze").register(meterRegistry);
        this.participantExclusion = Counter.builder("tc.ui.operation.participant.exclusion").register(meterRegistry);
        this.idempotencyConflict = Counter.builder("tc.ui.operation.digest.conflict").register(meterRegistry);
        this.replyProofRejected = Counter.builder("tc.ui.operation.reply.rejected").register(meterRegistry);
        this.finalizedRevisionNotRecorded =
                Counter.builder("tc.ui.operation.finalized.revision.not.recorded").register(meterRegistry);
        this.compensationIssued = Counter.builder("tc.ui.operation.compensation.issued").register(meterRegistry);
        this.operationRolledBack = Counter.builder("tc.ui.operation.rolled.back").register(meterRegistry);
        this.operationSteps = DistributionSummary.builder("tc.ui.operation.steps").register(meterRegistry);
        meterRegistry.gauge("tc.ui.operation.recovery.backlog", recoveryBacklog, AtomicInteger::get);
        meterRegistry.gauge("tc.ui.operation.executor.epoch", executorEpoch, AtomicLong::get);
    }

    /**
     * 한 번의 전진에서 수행한 전이 수를 기록합니다.
     *
     * @param steps 전이 수
     */
    public void recordOperationSteps(final int steps) {
        operationSteps.record(steps);
    }

    /** PASS 로 확정된 operation 을 셉니다. */
    public void incrementOperationPass() {
        operationPass.increment();
    }

    /** FAIL 로 확정된 operation 을 셉니다. */
    public void incrementOperationFail() {
        operationFail.increment();
    }

    /** 발급된 보상 operation 을 셉니다(§13.8). */
    public void incrementCompensationIssued() {
        compensationIssued.increment();
    }

    /** 보상 완료로 되돌려진(FAIL + ROLLED_BACK) operation 을 셉니다(§7.2). */
    public void incrementOperationRolledBack() {
        operationRolledBack.increment();
    }

    /** 수동 복구가 필요한 operation 을 셉니다. */
    public void incrementOperationRepairRequired() {
        operationRepairRequired.increment();
    }

    /** 커밋 전 participant 재고정 횟수를 셉니다. */
    public void incrementParticipantRefreeze() {
        participantRefreeze.increment();
    }

    /** 커밋 후 만료 participant 제외 횟수를 셉니다. */
    public void incrementParticipantExclusion() {
        participantExclusion.increment();
    }

    /** 같은 key 다른 digest 충돌을 셉니다. */
    public void incrementIdempotencyConflict() {
        idempotencyConflict.increment();
    }

    /** 조건 불일치로 거부된 reply proof 를 셉니다. */
    public void incrementReplyProofRejected() {
        replyProofRejected.increment();
    }

    /** 마지막 finalized revision 을 기록하지 못한 횟수를 셉니다. */
    public void incrementFinalizedRevisionNotRecorded() {
        finalizedRevisionNotRecorded.increment();
    }

    /**
     * 복구 대기 backlog 크기를 기록합니다.
     *
     * @param backlog 미종결 operation 수
     */
    public void recordRecoveryBacklog(final int backlog) {
        recoveryBacklog.set(backlog);
    }

    /**
     * 마지막으로 획득한 executor epoch 을 기록합니다.
     *
     * @param epoch executor epoch
     */
    public void recordExecutorEpoch(final long epoch) {
        executorEpoch.set(epoch);
    }

    /**
     * 현재 backlog 값을 반환합니다.
     *
     * @return backlog
     */
    public int recoveryBacklog() {
        return recoveryBacklog.get();
    }
}
