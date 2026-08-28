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
        UNEXPECTED_STATE,
        /**
         * 요청 자체가 거부됐습니다 — 같은 입력으로 다시 시도해도 결과가 같습니다.
         *
         * <p>{@code UNEXPECTED_STATE} 와 다릅니다. 저쪽은 "DB 가 알 수 없는 상태" 라 사람이 봐야
         * 하는 것이고, 이쪽은 "요청이 틀렸다" 가 이미 확정된 것입니다. 재시도로 구제되지 않는 실패를
         * 예외로 흘려보내면 reconciler 가 영원히 재시도하며 리소스 claim 을 붙잡고 pending backlog 를
         * 키웁니다 — 그 backlog 가 한도를 넘으면 mutation readiness 가 내려갑니다(§13.11).</p>
         */
        REJECTED
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

    /**
     * participant 가 검증·적용할 수 있도록 PREPARE 전에 DB 상태를 준비합니다(§16.1).
     *
     * <p>설비 생성에만 필요합니다. Gateway 의 설비 컨텍스트는 {@code eqp_key} 를 양수로 요구하고
     * 상태 이력도 {@code tc_eqp} 를 참조하므로, 행이 없는 동안에는 Gateway 가 적용을 끝낼 수 없습니다.
     * §16.1 은 이 경우 "DB row 가 prepare 과정에 임시 생성" 되는 것을 허용하며, 조건은 <b>비terminal
     * barrier 를 startup overlay 가 인식해 어떤 Pod 도 active 로 처리하지 않는 것</b>입니다.</p>
     *
     * <p>반복 호출에 안전해야 합니다 — 같은 operation 이 여러 패스에서 이 단계를 다시 지나갑니다.</p>
     *
     * @param operationId operation 식별자
     * @param manifest 원장에 고정된 manifest 뷰
     * @return 준비 결과({@code UNEXPECTED_STATE} 면 접수된 목표와 다른 행이 이미 있습니다)
     */
    default Outcome stageBeforePrepare(final String operationId, final UiOperationManifestView manifest) {
        return Outcome.ALREADY_AT_TARGET;
    }

    /**
     * commit 전 abort 로 끝난 operation 이 임시로 만든 DB 상태를 제거합니다(§16.1).
     *
     * <p>§16.1 은 "실패 시 임시 DB 상태가 제거/보상되었음을 증명해야 FAIL" 이라고 요구합니다. 제거하지
     * 않으면 아무도 쓰지 못하는 설비 행이 남고, 같은 eqpId 재요청은 중복 등록으로 거부됩니다.</p>
     *
     * @param operationId operation 식별자
     * @param manifest 원장에 고정된 manifest 뷰
     */
    default void unstageAfterAbort(final String operationId, final UiOperationManifestView manifest) {
        // 준비 단계가 없는 명령 종류는 되돌릴 것도 없습니다.
    }
}
