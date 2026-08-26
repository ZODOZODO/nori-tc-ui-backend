package com.nori.tc.ui.adapters.redis.coordination;

import com.nori.tc.common.coordination.error.CoordinationUnavailableException;
import com.nori.tc.ui.core.coordination.UiCoordinationUnavailableException;

import java.util.function.Supplier;

/**
 * 공통 조정 store 의 실패를 ui-core 예외로 번역합니다.
 *
 * <p>왜 어댑터에서 하는가: {@code tc-ui-core} 와 {@code tc-ui-web-adapter} 는 조정 라이브러리에
 * 의존하지 않는다(헥사고날 경계). 그래서 공통 {@link CoordinationUnavailableException} 을 그대로
 * 올리면 웹 계층이 그 타입을 매핑할 수 없어 <b>500 INTERNAL_ERROR</b> 로 나가고, §13.2 의
 * "조정 불가 = 503 COORDINATION_UNAVAILABLE" 계약과 §23.9 의 fail-closed 판정이 깨진다
 * (2026-08-26 P6 통합 검증에서 cluster epoch 변조 시 실제로 500 이 관측됐다).</p>
 *
 * <p>번역만 하고 삼키지 않는다 — 실패는 여전히 예외로 전파되어 접수/조회가 성공으로 보이지 않는다.</p>
 */
final class UiCoordinationFailures {

    private UiCoordinationFailures() {
    }

    /**
     * 값을 돌려주는 store 호출을 번역 경계 안에서 실행합니다.
     *
     * @param call store 호출
     * @param <T> 반환 타입
     * @return 호출 결과
     */
    static <T> T translating(final Supplier<T> call) {
        try {
            return call.get();
        } catch (CoordinationUnavailableException ex) {
            throw new UiCoordinationUnavailableException(ex.getMessage());
        }
    }
}
