package com.nori.tc.ui.core.coordination.model;

import java.util.Objects;

/**
 * 고정된 participant 하나의 prepare/applied proof 스냅샷입니다(§20.3).
 *
 * <p>{@code STATUS} 만으로 적용을 판정하지 않기 위한 증명입니다 — 어느 session 이 어느
 * revision/digest 를, 어느 owner epoch 아래에서 준비하고 적용했는지가 있어야 운영자가 barrier 가
 * 열리지 않은 이유를 읽을 수 있습니다.</p>
 *
 * <p>{@code prepared*} 만 있고 {@code applied*} 가 비어 있으면 그 participant 는 준비까지만 마친
 * 상태입니다 — commit 이후 여기서 멈춰 있으면 §13.8 의 복구 대상입니다.</p>
 *
 * @param participantId 고정 집합의 session 식별자
 * @param preparedRevision 준비를 마친 revision(미준비면 null)
 * @param preparedDigest 준비 시점의 manifest digest(미준비면 null)
 * @param appliedRevision 적용을 마친 revision(미적용이면 null)
 * @param appliedDigest 적용 시점의 manifest digest(미적용이면 null)
 * @param ownerEpoch Gateway participant 의 owner epoch(Business 는 null)
 */
public record UiParticipantProofView(
        String participantId,
        Long preparedRevision,
        String preparedDigest,
        Long appliedRevision,
        String appliedDigest,
        Long ownerEpoch
) {

    /**
     * 필수 값을 검증합니다.
     */
    public UiParticipantProofView {
        Objects.requireNonNull(participantId, "participantId is null");
    }
}
