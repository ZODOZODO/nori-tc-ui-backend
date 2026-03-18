package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;

/**
 * UI DB 어댑터의 페이징 목록 응답에서 전체 건수(count) 계산을 보조하는 유틸리티입니다.
 *
 * <p>배경:</p>
 * <p>현재 {@code tc-db-core} Store 계약은 count 전용 메서드를 제공하지 않습니다.
 * 따라서 목록 응답의 {@code count}를 정확하게 채우기 위해 페이지 스캔이 필요할 수 있습니다.</p>
 *
 * <p>동작 전략:</p>
 * <ol>
 *   <li>현재 페이지가 마지막 페이지임이 명확하면(조회 건수 &lt; limit) 즉시 계산</li>
 *   <li>마지막 페이지를 확정할 수 없으면 0-offset부터 별도 스캔하여 전체 건수 집계</li>
 * </ol>
 */
final class UiDbPagedCountSupport {

    /**
     * count 스캔 기본 페이지 크기입니다.
     *
     * <p>일반 목록 조회 limit와 분리해, count 스캔은 고정된 중간 크기 페이지로
     * I/O 횟수와 메모리 사용량을 균형 있게 유지합니다.</p>
     */
    static final int DEFAULT_COUNT_SCAN_LIMIT = 500;

    /**
     * 유틸리티 클래스 인스턴스화를 방지합니다.
     */
    private UiDbPagedCountSupport() {
        // 정적 유틸리티 전용 클래스입니다.
    }

    /**
     * 페이지 목록 응답의 전체 건수(count)를 계산합니다.
     *
     * @param currentItems 현재 요청 페이지의 조회 결과
     * @param currentPage 현재 요청 페이지 정보
     * @param countScanLimit count 스캔 시 사용할 페이지 크기
     * @param pageFetcher 페이지 조회 함수
     * @param log 로깅 객체
     * @param resourceName 로그 식별용 자원명(예: model/user/group)
     * @param <T> 목록 요소 타입
     * @return 전체 건수(count)
     */
    static <T> long resolveTotalCount(
            final List<T> currentItems,
            final PageRequest currentPage,
            final int countScanLimit,
            final PageFetcher<T> pageFetcher,
            final Logger log,
            final String resourceName
    ) {
        Objects.requireNonNull(currentItems, "currentItems is null");
        Objects.requireNonNull(currentPage, "currentPage is null");
        Objects.requireNonNull(pageFetcher, "pageFetcher is null");
        Objects.requireNonNull(log, "log is null");
        Objects.requireNonNull(resourceName, "resourceName is null");

        if (countScanLimit <= 0) {
            throw new IllegalArgumentException("countScanLimit must be > 0");
        }

        // 현재 페이지가 마지막 페이지임이 확정되는 경우에는 추가 DB 스캔 없이 총 건수를 계산할 수 있습니다.
        if (currentItems.size() < currentPage.limit()) {
            final long inferredCount = (long) currentPage.offset() + currentItems.size();
            if (log.isTraceEnabled()) {
                log.trace("{} count fast-path 적용. offset={}, limit={}, pageSize={}, inferredCount={}",
                        resourceName, currentPage.offset(), currentPage.limit(), currentItems.size(), inferredCount);
            }
            return inferredCount;
        }

        long totalCount = 0L;
        int offset = 0;
        int chunkNo = 0;

        while (true) {
            final List<T> chunk = pageFetcher.fetch(PageRequest.of(offset, countScanLimit));
            totalCount += chunk.size();

            if (log.isTraceEnabled()) {
                log.trace("{} count scan chunk. chunkNo={}, offset={}, limit={}, chunkSize={}, accumulatedCount={}",
                        resourceName, chunkNo, offset, countScanLimit, chunk.size(), totalCount);
            }

            if (chunk.size() < countScanLimit) {
                return totalCount;
            }

            if (offset > Integer.MAX_VALUE - countScanLimit) {
                throw new IllegalStateException(resourceName + " count scan offset overflow");
            }
            offset += countScanLimit;
            chunkNo++;
        }
    }

    /**
     * 페이지 단위 목록 조회 함수를 표현하는 함수형 인터페이스입니다.
     *
     * @param <T> 목록 요소 타입
     */
    @FunctionalInterface
    interface PageFetcher<T> {
        /**
         * 페이지 요청에 맞는 목록을 조회합니다.
         *
         * @param pageRequest offset/limit 요청
         * @return 조회된 목록
         */
        List<T> fetch(PageRequest pageRequest);
    }
}
