package com.nori.tc.ui.core.model;

import java.util.Objects;

/**
 * reply 에 실린 coordination proof 입니다(§10.2).
 *
 * <p>{@code STATUS} 만으로 operation 성공을 판정하지 않기 위한 증명 정보입니다 — 어느 session 이,
 * 어느 revision/digest 를, 어느 owner epoch 아래에서 적용했는지가 있어야 원장이 그 proof 를 받아들일지
 * 판단할 수 있습니다.</p>
 *
 * @param operationId 대상 operation
 * @param commandState {@code PREPARED} / {@code REJECTED} / {@code APPLIED} / {@code ABORTED} / {@code STALE_FENCED}
 * @param serviceSessionId 응답 주체 session
 * @param ownerEpoch Gateway owner epoch(Business 응답은 null)
 * @param appliedRevision 적용한 revision(미적용이면 null)
 * @param desiredRevision 요청 desired revision
 * @param payloadSha256 요청 manifest digest
 */
public record UiCommandReplyProof(
        String operationId,
        String commandState,
        String serviceSessionId,
        Long ownerEpoch,
        Long appliedRevision,
        Long desiredRevision,
        String payloadSha256
) {

    /** participant 가 candidate 준비를 마쳤음을 뜻합니다. */
    public static final String STATE_PREPARED = "PREPARED";

    /** participant 가 commit 전 검증에서 거부했음을 뜻합니다. */
    public static final String STATE_REJECTED = "REJECTED";

    /** participant 가 candidate 를 active runtime 으로 적용했음을 뜻합니다. */
    public static final String STATE_APPLIED = "APPLIED";

    /** participant 가 candidate 를 폐기했음을 뜻합니다. */
    public static final String STATE_ABORTED = "ABORTED";

    /** 소유권을 잃은 session 의 응답임을 뜻합니다. */
    public static final String STATE_STALE_FENCED = "STALE_FENCED";

    /**
     * proof 완결성을 검증합니다.
     */
    public UiCommandReplyProof {
        Objects.requireNonNull(operationId, "operationId is null");
        Objects.requireNonNull(commandState, "commandState is null");
        Objects.requireNonNull(serviceSessionId, "serviceSessionId is null");
        Objects.requireNonNull(desiredRevision, "desiredRevision is null");
        Objects.requireNonNull(payloadSha256, "payloadSha256 is null");
    }

    /**
     * 이 응답이 적용 완료를 주장하는지 반환합니다.
     *
     * @return commandState 가 APPLIED 이면 true
     */
    public boolean claimsApplied() {
        return STATE_APPLIED.equals(commandState);
    }

    /**
     * 이 응답이 commit 전 거부를 주장하는지 반환합니다.
     *
     * @return commandState 가 REJECTED 또는 ABORTED 이면 true
     */
    public boolean claimsRejected() {
        return STATE_REJECTED.equals(commandState) || STATE_ABORTED.equals(commandState);
    }
}
