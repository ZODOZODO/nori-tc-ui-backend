package com.nori.tc.ui.core.coordination.model;

import java.util.Objects;

/**
 * operation 의 phase 전이 한 건입니다(§20.3).
 *
 * <p>운영자가 "어디서 멈췄는지" 가 아니라 "어떤 순서로 여기까지 왔는지" 를 읽는 데 씁니다 —
 * 현재 phase 만으로는 되돌아온 전이나 반복 재시도를 구분할 수 없습니다.</p>
 *
 * @param phase 전이한 phase 이름
 * @param atEpochMs 전이 시각(epoch millis)
 */
public record UiPhaseTransitionView(String phase, long atEpochMs) {

    /**
     * 필수 값을 검증합니다.
     */
    public UiPhaseTransitionView {
        Objects.requireNonNull(phase, "phase is null");
    }
}
