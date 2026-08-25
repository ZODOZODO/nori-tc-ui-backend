package com.nori.tc.ui.core.coordination.model;

/**
 * operation 의 terminal 여부를 나타내는 상태입니다(§7.2).
 *
 * <p>관찰 deadline / HTTP timeout / Kafka reply timeout 은 여기에 값을 만들지 않는다 — §13.3 이
 * TIMEOUT terminal 생성을 금지한다.</p>
 */
public enum UiOperationState {

    /** 비terminal. 진행 중이거나 복구 중입니다. */
    PENDING,

    /** 장벽 개방과 terminal 결과 저장이 끝난 성공 상태입니다. */
    PASS,

    /** 확정된 실패 또는 완료된 rollback 입니다. */
    FAIL,

    /** 보상을 증명할 수 없어 수동 복구가 필요한 상태입니다. */
    REPAIR_REQUIRED,

    /** 원장 값이 이 버전에서 해석되지 않는 경우입니다 — 성공으로 축약하지 않습니다. */
    UNKNOWN
}
