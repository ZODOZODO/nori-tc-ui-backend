package com.nori.tc.ui.core.model;

/**
 * 비동기 작업(eq p_start / eqp_end) polling 상태 코드입니다.
 *
 * <p>프론트 polling API {@code GET /api/async/{traceId}}의 HTTP 상태 코드와
 * 1:1로 대응되도록 설계합니다.</p>
 *
 * <ul>
 *   <li>{@link #PENDING}: 아직 Gateway 응답이 도착하지 않은 처리 중 상태 (HTTP 202)</li>
 *   <li>{@link #COMPLETED}: Gateway 응답이 도착하여 최종 결과가 저장된 상태 (HTTP 200)</li>
 *   <li>{@link #TIMEOUT}: 지정된 대기 시간을 초과하여 타임아웃 처리된 상태 (HTTP 408)</li>
 * </ul>
 */
public enum AsyncStatus {

    /**
     * 처리 중 상태입니다.
     */
    PENDING,

    /**
     * 처리 완료 상태입니다.
     */
    COMPLETED,

    /**
     * 처리 타임아웃 상태입니다.
     */
    TIMEOUT
}

