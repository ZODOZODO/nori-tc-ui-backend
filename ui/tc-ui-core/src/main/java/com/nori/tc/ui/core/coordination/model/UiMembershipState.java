package com.nori.tc.ui.core.coordination.model;

/**
 * UI Backend session 의 membership 상태입니다(§9.2).
 *
 * <p>{@code FENCED} 는 프로세스 안에서도 종착 상태다 — 나가는 전이를 허용하면 로컬 상태가 되살아나
 * 이미 소유권을 잃은 session 이 executor 전이를 계속한다(Plan §8 B22).</p>
 */
public enum UiMembershipState {

    /** 등록은 했으나 아직 원장 sync 를 마치지 않은 상태입니다. */
    JOINING,

    /** 원장 sync 를 마치고 처리 가능한 상태입니다. */
    ACTIVE_SYNCED,

    /** 처리 중단(quiesce) 상태입니다. */
    QUIESCED,

    /** 소유권을 상실해 어떤 확정도 수행할 수 없는 종착 상태입니다. */
    FENCED
}
