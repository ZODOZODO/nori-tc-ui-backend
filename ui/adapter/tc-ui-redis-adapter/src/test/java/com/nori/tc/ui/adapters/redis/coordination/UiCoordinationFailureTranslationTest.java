package com.nori.tc.ui.adapters.redis.coordination;

import com.nori.tc.common.coordination.error.CoordinationUnavailableException;
import com.nori.tc.ui.core.coordination.UiCoordinationUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 공통 조정 실패가 ui-core 예외로 번역되는지 고정합니다.
 *
 * <p>번역하지 않으면 웹 계층이 타입을 몰라 <b>500 INTERNAL_ERROR</b> 로 나간다 — §13.2 는 503
 * COORDINATION_UNAVAILABLE 을, §23.9 는 fail-closed 를 요구한다. 2026-08-26 P6 통합 검증에서
 * cluster epoch 를 변조하자 실제로 500 이 관측됐다.</p>
 */
class UiCoordinationFailureTranslationTest {

    @Test
    @DisplayName("공통 조정 불가 예외는 ui 예외로 번역된다")
    void coordinationUnavailableIsTranslated() {
        final UiCoordinationUnavailableException thrown = assertThrows(
                UiCoordinationUnavailableException.class,
                () -> UiCoordinationFailures.translating(() -> {
                    throw new CoordinationUnavailableException("cluster epoch mismatch");
                }));

        assertEquals("cluster epoch mismatch", thrown.getMessage());
    }

    @Test
    @DisplayName("다른 예외는 그대로 전파한다 — 번역이 실패를 삼키지 않는다")
    void otherFailuresPropagateUnchanged() {
        final IllegalStateException cause = new IllegalStateException("boom");

        final IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> UiCoordinationFailures.translating(() -> {
                    throw cause;
                }));

        assertSame(cause, thrown);
    }

    @Test
    @DisplayName("정상 경로는 값을 그대로 돌려준다")
    void successfulCallReturnsValue() {
        assertEquals("OK", UiCoordinationFailures.translating(() -> "OK"));
    }
}
