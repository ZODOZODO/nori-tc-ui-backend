package com.nori.tc.ui.core.registry;

import java.util.Objects;

/**
 * Redis에 저장된 DualResponse 조회 결과입니다.
 *
 * <p>단순 미완료(PENDING)와 인프라 조회 실패(INFRA_ERROR)를 구분해
 * 상위 오케스트레이션이 장애를 정상 미완료로 오해하지 않도록 합니다.</p>
 */
public record DualResponseRedisLookupResult(
        Status status,
        UiDualTaskFinalResult result,
        String errorMessage
) {

    public DualResponseRedisLookupResult {
        Objects.requireNonNull(status, "status is null");
        if (status == Status.COMPLETED && result == null) {
            throw new IllegalArgumentException("COMPLETED lookup에는 result가 필요합니다.");
        }
        if (status != Status.COMPLETED && result != null) {
            throw new IllegalArgumentException("COMPLETED가 아닌 lookup에는 result를 담을 수 없습니다.");
        }
    }

    public static DualResponseRedisLookupResult pending() {
        return new DualResponseRedisLookupResult(Status.PENDING, null, null);
    }

    public static DualResponseRedisLookupResult completed(final UiDualTaskFinalResult result) {
        return new DualResponseRedisLookupResult(Status.COMPLETED, Objects.requireNonNull(result, "result is null"), null);
    }

    public static DualResponseRedisLookupResult infraError(final String errorMessage) {
        return new DualResponseRedisLookupResult(Status.INFRA_ERROR, null, normalize(errorMessage));
    }

    private static String normalize(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public enum Status {
        PENDING,
        COMPLETED,
        INFRA_ERROR
    }
}
