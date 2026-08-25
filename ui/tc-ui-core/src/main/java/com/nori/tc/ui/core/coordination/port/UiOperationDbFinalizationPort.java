package com.nori.tc.ui.core.coordination.port;

import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;

/**
 * committed revision 을 업무 DB 에 idempotent 하게 확정하는 포트입니다(§13.7).
 */
public interface UiOperationDbFinalizationPort {

    /** finalization 결과입니다. */
    enum Outcome {
        /** 이번 호출이 DB 를 목표 상태로 바꿨습니다. */
        FINALIZED,
        /** DB 가 이미 목표 상태라 성공으로 reconcile 했습니다. */
        ALREADY_AT_TARGET,
        /**
         * DB 가 예상 old/desired 어느 쪽도 아닙니다.
         *
         * <p>blind overwrite 하지 않고 {@code REPAIR_REQUIRED} 로 전환합니다 — 알 수 없는 상태를
         * 덮어쓰면 무엇이 유실됐는지 사후에 알 수 없습니다.</p>
         */
        UNEXPECTED_STATE
    }

    /**
     * manifest 가 표현하는 목표 상태로 DB 를 확정합니다.
     *
     * <p>expected base value/version 을 검증하는 idempotent transaction 이어야 하며, executor crash 후
     * 같은 호출을 반복해도 같은 결과가 나와야 합니다.</p>
     *
     * @param operationId operation 식별자
     * @param manifest 원장에 고정된 manifest 뷰
     * @param desiredRevision 확정할 revision
     * @return 확정 결과
     */
    Outcome finalizeToTarget(String operationId, UiOperationManifestView manifest, long desiredRevision);
}
