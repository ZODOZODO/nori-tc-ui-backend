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
 *
 * <p>Redis 를 타는 조정 어댑터는 <b>모두</b> 이 경계를 통과한다. HTTP 도달 여부로 고르지 않는다 —
 * core 가 공통 예외 타입을 참조할 수 없는 이상, 번역 누락은 그 자체가 계층 위반이고 새 HTTP 경로가
 * 생길 때마다 같은 500 이 재발한다. 제외는 둘뿐이며 이유가 다르다:
 * {@code CommonCoordinationDigestAdapter} 는 Redis 를 타지 않는 순수 계산이고,
 * {@code RedisUiCoordinationProbeAdapter} 는 readiness probe 라 예외를 던지지 않고 false 를
 * 돌려주는 것이 계약이다.</p>
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
