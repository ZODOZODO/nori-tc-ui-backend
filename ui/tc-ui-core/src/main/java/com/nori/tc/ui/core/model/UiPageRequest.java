package com.nori.tc.ui.core.model;

/**
 * UI Core 전용 페이징 요청 객체입니다.
 *
 * <p>코어 계층은 DB 공통 계약 타입을 직접 알지 않도록
 * offset/limit 의미만 유지하는 자체 요청 모델을 사용합니다.</p>
 */
public record UiPageRequest(int offset, int limit) {

    public static UiPageRequest of(final int offset, final int limit) {
        return new UiPageRequest(offset, limit);
    }

    public static UiPageRequest defaultPage() {
        return new UiPageRequest(0, 100);
    }

    public UiPageRequest {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
    }
}
