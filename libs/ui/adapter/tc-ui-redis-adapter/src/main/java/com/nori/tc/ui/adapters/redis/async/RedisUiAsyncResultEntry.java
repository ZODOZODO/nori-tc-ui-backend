package com.nori.tc.ui.adapters.redis.async;

import com.nori.tc.ui.core.model.AsyncResultEntry;
import com.nori.tc.ui.core.model.AsyncStatus;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.domain.task.UiTaskStatus;

/**
 * Redis 저장용 비동기 작업 결과 엔트리입니다.
 *
 * <p>역할:</p>
 * <p>{@link UiCommandReply}를 Redis JSON 직렬화로 저장하기 위한 어댑터 전용 DTO입니다.
 * JDK 직렬화 제거로 역직렬화 공격 표면을 줄입니다.</p>
 *
 * <p>키 형식: {@code tc:ui:backend:async:{traceId}}</p>
 * <p>TTL: {@code tc.ui.backend.async.result-ttl-seconds} 설정값을 따릅니다 (기본 600초).</p>
 *
 * <p>직렬화 호환성:</p>
 * <p>필드 구조가 바뀌면 기존 Redis 값과 호환되지 않을 수 있으므로
 * 배포 시 캐시 정리 정책을 함께 적용해야 합니다.</p>
 */
public class RedisUiAsyncResultEntry {

    // --- async 상태 관리 필드 ---

    /** polling 상태(PENDING / COMPLETED / TIMEOUT) */
    private String asyncStatus;

    /** PENDING 상태의 타임아웃 예정 시각(epoch ms) */
    private Long timeoutAtEpochMs;

    // --- metadata 필드 (KafkaUiTaskMessage.KafkaUiTaskMetadata에서 복사) ---

    /** 이벤트 타입 (예: EQP_START_REP, EQP_END_REP) */
    private String eventType;

    /** 메시지 발행 출처 (예: TC-COMM-GATEWAY, TC-BUSINESS-CORE) */
    private String source;

    /** 작업 추적 ID */
    private String traceId;

    // --- data 필드 (KafkaUiTaskReplyMessage.KafkaUiTaskReplyData에서 복사) ---

    /** 설비 ID */
    private String eqpId;

    /** 인터페이스 타입 */
    private String interfaceType;

    /** 처리 결과 (PASS / FAIL) - COMPLETED 상태에서만 의미가 있습니다. */
    private String status;

    /** 오류 메시지 (실패 시 설정) */
    private String errorMsg;

    /** 오류 코드 (실패 시 설정) */
    private String errorCode;

    /**
     * Redis 직렬화 프레임워크용 기본 생성자입니다.
     */
    protected RedisUiAsyncResultEntry() {
    }

    /**
     * PENDING 상태 엔트리를 생성합니다.
     *
     * @param traceId 작업 추적 ID
     * @param timeoutAtEpochMs 타임아웃 예정 시각(epoch ms)
     * @return Redis 저장용 엔트리
     */
    public static RedisUiAsyncResultEntry pending(
            final String traceId,
            final long timeoutAtEpochMs
    ) {
        final RedisUiAsyncResultEntry entry = new RedisUiAsyncResultEntry();
        entry.traceId = traceId;
        entry.asyncStatus = AsyncStatus.PENDING.name();
        entry.timeoutAtEpochMs = timeoutAtEpochMs;
        return entry;
    }

    /**
     * COMPLETED 상태 엔트리를 생성합니다.
     *
     * @param reply 완료 응답
     * @return Redis 저장용 엔트리
     */
    public static RedisUiAsyncResultEntry completed(final UiCommandReply reply) {
        final RedisUiAsyncResultEntry entry = new RedisUiAsyncResultEntry();
        entry.asyncStatus = AsyncStatus.COMPLETED.name();
        entry.traceId = reply.traceId();
        entry.eventType = reply.eventType();
        entry.source = reply.source();
        entry.eqpId = reply.eqpId();
        entry.interfaceType = reply.interfaceType();
        entry.status = reply.status().name();
        entry.errorMsg = reply.errorMsg();
        entry.errorCode = reply.errorCode();
        return entry;
    }

    /**
     * TIMEOUT 상태 엔트리를 생성합니다.
     *
     * @param traceId 작업 추적 ID
     * @return Redis 저장용 엔트리
     */
    public static RedisUiAsyncResultEntry timeout(final String traceId) {
        final RedisUiAsyncResultEntry entry = new RedisUiAsyncResultEntry();
        entry.traceId = traceId;
        entry.asyncStatus = AsyncStatus.TIMEOUT.name();
        return entry;
    }

    /**
     * Redis 엔트리를 core 상태 모델로 복원합니다.
     *
     * @return 복원된 AsyncResultEntry
     */
    public AsyncResultEntry toAsyncResultEntry() {
        final AsyncStatus parsedAsyncStatus;
        try {
            parsedAsyncStatus = AsyncStatus.valueOf(asyncStatus);
        } catch (Exception ex) {
            throw new IllegalStateException("유효하지 않은 asyncStatus 입니다. value=" + asyncStatus, ex);
        }

        if (parsedAsyncStatus == AsyncStatus.PENDING) {
            final long timeoutAt = timeoutAtEpochMs == null ? 0L : timeoutAtEpochMs;
            if (timeoutAt <= 0L) {
                // 과거 포맷/비정상 데이터 방어: 타임아웃 정보가 없으면 보수적으로 TIMEOUT으로 간주합니다.
                return AsyncResultEntry.timeout(traceId);
            }
            return AsyncResultEntry.pending(traceId, timeoutAt);
        }

        if (parsedAsyncStatus == AsyncStatus.TIMEOUT) {
            return AsyncResultEntry.timeout(traceId);
        }

        final UiTaskStatus parsedStatus;
        try {
            parsedStatus = UiTaskStatus.valueOf(status);
        } catch (Exception ex) {
            throw new IllegalStateException("유효하지 않은 reply status 입니다. value=" + status, ex);
        }
        final UiCommandReply reply = new UiCommandReply(
                traceId,
                source,
                eventType,
                eqpId,
                interfaceType,
                parsedStatus,
                errorCode,
                errorMsg
        );
        return AsyncResultEntry.completed(traceId, reply);
    }

    /**
     * PENDING 상태인지 여부를 반환합니다.
     *
     * @return PENDING 상태면 true
     */
    public boolean isPending() {
        return AsyncStatus.PENDING.name().equals(asyncStatus);
    }

    /**
     * 타임아웃 예정 시각(epoch ms)을 반환합니다.
     *
     * @return 타임아웃 예정 시각 (없으면 0)
     */
    public long getTimeoutAtEpochMs() {
        return timeoutAtEpochMs == null ? 0L : timeoutAtEpochMs;
    }

    /**
     * 상태 값을 문자열로 반환합니다.
     *
     * @return 상태 문자열
     */
    public String getAsyncStatus() {
        return asyncStatus;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getSource() {
        return source;
    }

    public String getEqpId() {
        return eqpId;
    }

    public String getInterfaceType() {
        return interfaceType;
    }

    public String getStatus() {
        return status;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
