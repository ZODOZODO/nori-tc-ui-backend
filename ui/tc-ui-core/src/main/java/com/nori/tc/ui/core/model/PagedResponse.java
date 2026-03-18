package com.nori.tc.ui.core.model;

import java.util.List;

/**
 * 목록 조회 응답을 공통 형태로 전달하기 위한 페이지 응답 계약입니다.
 *
 * <p>UI 관리 페이지의 목록 API는 모두 동일한 응답 구조를 사용합니다.</p>
 * <ul>
 *   <li>{@code items}: 현재 페이지에 포함된 실제 데이터 목록</li>
 *   <li>{@code offset}: 전체 목록에서 건너뛴 행 수</li>
 *   <li>{@code limit}: 한 번에 조회한 최대 행 수</li>
 *   <li>{@code count}: 조건에 맞는 전체 행 수</li>
 * </ul>
 *
 * <p>주의 사항:</p>
 * <ul>
 *   <li>{@code items}는 불변 리스트로 복사되어 외부에서 수정할 수 없습니다.</li>
 *   <li>{@code count}는 현재 페이지 크기({@code items.size()})보다 작을 수 없습니다.</li>
 * </ul>
 *
 * @param <T> 목록 원소 타입
 * @param items 현재 페이지 데이터
 * @param offset 조회 시작 위치(0 이상)
 * @param limit 페이지 크기(1 이상)
 * @param count 전체 건수(0 이상)
 */
public record PagedResponse<T>(
        List<T> items,
        int offset,
        int limit,
        long count
) {

    /**
     * 생성 시점에 페이지 응답 불변식을 검증합니다.
     */
    public PagedResponse {
        final List<T> normalizedItems = (items == null) ? List.of() : List.copyOf(items);
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        if (count < 0L) {
            throw new IllegalArgumentException("count must be >= 0");
        }
        if (count < normalizedItems.size()) {
            throw new IllegalArgumentException("count must be >= items.size()");
        }
        items = normalizedItems;
    }

    /**
     * 기본 생성 팩토리입니다.
     *
     * @param items 페이지 데이터 목록
     * @param offset 조회 시작 위치
     * @param limit 조회 크기
     * @param count 전체 건수
     * @param <T> 목록 원소 타입
     * @return 검증된 페이지 응답 객체
     */
    public static <T> PagedResponse<T> of(
            final List<T> items,
            final int offset,
            final int limit,
            final long count
    ) {
        return new PagedResponse<>(items, offset, limit, count);
    }

    /**
     * 데이터가 없는 페이지 응답을 생성합니다.
     *
     * @param offset 조회 시작 위치
     * @param limit 조회 크기
     * @param <T> 목록 원소 타입
     * @return 빈 목록 페이지 응답(count=0)
     */
    public static <T> PagedResponse<T> empty(final int offset, final int limit) {
        return new PagedResponse<>(List.of(), offset, limit, 0L);
    }
}
