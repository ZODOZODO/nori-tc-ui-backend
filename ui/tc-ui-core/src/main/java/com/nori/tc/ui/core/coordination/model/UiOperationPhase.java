package com.nori.tc.ui.core.coordination.model;

/**
 * operation 의 상세 phase 입니다(§7.2 9상태 + 비terminal 복구 phase).
 */
public enum UiOperationPhase {

    /** operation record 를 원자 생성한 상태입니다. */
    ACCEPTED,

    /** participant 가 candidate 를 검증·준비하는 상태입니다. */
    PREPARING,

    /** 영향 리소스 장벽을 닫고 in-flight 를 drain 하는 상태입니다. */
    QUIESCING,

    /** 고정 participant 전원이 같은 candidate digest/base revision 을 확인한 상태입니다. */
    PREPARED,

    /** resource head 가 새 desired revision 으로 전환된 상태입니다. */
    COMMITTED,

    /** participant 가 닫힌 장벽 아래에서 swap 하고 applied proof 를 남기는 상태입니다. */
    APPLYING,

    /** 업무 DB 를 목표 상태로 idempotent finalization 하는 상태입니다. */
    FINALIZING,

    /** DB finalization 성공 후 장벽을 연 상태입니다. */
    BARRIER_OPEN,

    /** 복구 실행 중인 비terminal phase 입니다. */
    RECONCILING,

    /** commit 후 실패를 이전 manifest 로 되돌리는 중입니다. */
    COMPENSATING,

    /** commit 전 실패로 candidate 를 폐기한 상태입니다. */
    ABORTED,

    /** 보상 revision 의 장벽이 열리고 DB 가 이전 상태로 복원되어 FAIL 로 확정된 상태입니다(§7.2). */
    ROLLED_BACK,

    /** 원장 값이 이 버전에서 해석되지 않는 경우입니다. */
    UNKNOWN
}
