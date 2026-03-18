package com.nori.tc.ui.core.model;

import java.util.Objects;

/**
 * 비동기 polling 상태 조회 결과 모델입니다.
 *
 * <p>이 모델은 {@link com.nori.tc.ui.core.port.redis.AsyncResultStorePort}가
 * traceId별 현재 상태를 반환할 때 사용합니다.</p>
 *
 * <p>상태별 데이터 규칙:</p>
 * <ul>
 *   <li>{@link AsyncStatus#PENDING}: {@code reply == null}, {@code timeoutAtEpochMs > 0}</li>
 *   <li>{@link AsyncStatus#COMPLETED}: {@code reply != null}, {@code timeoutAtEpochMs}는 0 허용</li>
 *   <li>{@link AsyncStatus#TIMEOUT}: {@code reply == null}, {@code timeoutAtEpochMs}는 0 허용</li>
 * </ul>
 *
 * @param traceId 작업 추적 ID
 * @param status 비동기 상태 코드
 * @param reply 완료 상태일 때의 최종 응답 payload (PENDING/TIMEOUT에서는 null)
 * @param timeoutAtEpochMs PENDING 상태의 타임아웃 예정 시각(epoch ms). 그 외 상태는 0
 */
public record AsyncResultEntry(
        String traceId,
        AsyncStatus status,
        UiCommandReply reply,
        long timeoutAtEpochMs
) {

    /**
     * 생성 시 상태 불변식을 검증합니다.
     */
    public AsyncResultEntry {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId is required");
        }
        Objects.requireNonNull(status, "status is null");

        switch (status) {
            case PENDING -> {
                if (reply != null) {
                    throw new IllegalArgumentException("PENDING status must not include reply");
                }
                if (timeoutAtEpochMs <= 0L) {
                    throw new IllegalArgumentException("PENDING status requires timeoutAtEpochMs > 0");
                }
            }
            case COMPLETED -> {
                if (reply == null) {
                    throw new IllegalArgumentException("COMPLETED status requires reply");
                }
            }
            case TIMEOUT -> {
                if (reply != null) {
                    throw new IllegalArgumentException("TIMEOUT status must not include reply");
                }
            }
            default -> throw new IllegalStateException("Unsupported status: " + status);
        }
    }

    /**
     * 처리 중 상태 엔트리를 생성합니다.
     *
     * @param traceId 작업 추적 ID
     * @param timeoutAtEpochMs 타임아웃 예정 시각(epoch ms)
     * @return PENDING 상태 엔트리
     */
    public static AsyncResultEntry pending(final String traceId, final long timeoutAtEpochMs) {
        return new AsyncResultEntry(traceId, AsyncStatus.PENDING, null, timeoutAtEpochMs);
    }

    /**
     * 처리 완료 상태 엔트리를 생성합니다.
     *
     * @param traceId 작업 추적 ID
     * @param reply 최종 응답 payload
     * @return COMPLETED 상태 엔트리
     */
    public static AsyncResultEntry completed(final String traceId, final UiCommandReply reply) {
        return new AsyncResultEntry(traceId, AsyncStatus.COMPLETED, reply, 0L);
    }

    /**
     * 타임아웃 상태 엔트리를 생성합니다.
     *
     * @param traceId 작업 추적 ID
     * @return TIMEOUT 상태 엔트리
     */
    public static AsyncResultEntry timeout(final String traceId) {
        return new AsyncResultEntry(traceId, AsyncStatus.TIMEOUT, null, 0L);
    }
}

