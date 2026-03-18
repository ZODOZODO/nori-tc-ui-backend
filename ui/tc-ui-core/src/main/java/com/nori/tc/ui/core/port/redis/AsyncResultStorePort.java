package com.nori.tc.ui.core.port.redis;

import com.nori.tc.ui.core.model.AsyncResultEntry;
import com.nori.tc.ui.core.model.UiCommandReply;

import java.util.Optional;

/**
 * 비동기 작업 결과 임시 저장 포트입니다.
 *
 * <p>역할:</p>
 * <p>eqp_start / eqp_end 요청에 대한 gateway 응답(tc.ui.commands 수신)을
 * Business Redis(6380)에 임시 저장합니다.
 * front는 202 즉시 반환 후 GET /api/async/{traceId}로 polling하여 결과를 확인합니다.</p>
 *
 * <p>캐시 키 형식:</p>
 * <pre>tc:ui:backend:async:{traceId}</pre>
 *
 * <p>TTL:</p>
 * <p>{@code tc.ui.backend.async.result-ttl-seconds} 설정값을 따릅니다 (기본 600초).</p>
 *
 * <p>구현체:</p>
 * <p>tc-ui-redis-adapter의 AsyncResultStoreService가 이 인터페이스를 구현합니다.</p>
 */
public interface AsyncResultStorePort {

    /**
     * 비동기 작업 대기(PENDING) 상태를 등록합니다.
     *
     * <p>EqpController가 EQP_START / EQP_END 명령 발행 직전에 호출합니다.
     * traceId를 먼저 등록하여, 응답이 빠르게 도착하는 경우에도 polling 경로에서
     * "존재하지 않는 traceId(404)"로 오인하지 않도록 보장합니다.</p>
     *
     * @param traceId 작업 추적 ID (ULID, 캐시 키 생성에 사용)
     * @param timeoutMs 비동기 처리 타임아웃(ms). 구현체는 timeoutMs + buffer를 사용해 TIMEOUT 전환 시점을 계산합니다.
     */
    void registerPending(String traceId, long timeoutMs);

    /**
     * 비동기 작업 완료(COMPLETED) 상태를 저장합니다.
     *
     * <p>UiCommandIngressService가 tc.ui.commands에서 EQP_START_REP / EQP_END_REP
     * 메시지를 수신했을 때 호출합니다.</p>
     *
     * @param traceId 작업 추적 ID
     * @param reply 최종 응답 DTO
     */
    void markCompleted(String traceId, UiCommandReply reply);

    /**
     * 비동기 작업을 타임아웃(TIMEOUT) 상태로 전환합니다.
     *
     * <p>스케줄러 또는 지연 확인 로직에서 호출합니다.
     * 이미 COMPLETED 상태인 경우에는 상태를 덮어쓰지 않아야 합니다.</p>
     *
     * @param traceId 작업 추적 ID
     */
    void markTimeout(String traceId);

    /**
     * 비동기 작업 상태를 조회합니다.
     *
     * <p>AsyncResultController가 polling 요청을 처리할 때 호출합니다.</p>
     *
     * @param traceId 조회할 작업 추적 ID
     * @return 상태 엔트리가 있으면 {@link AsyncResultEntry}, 없으면 빈 Optional
     */
    Optional<AsyncResultEntry> getWithStatus(String traceId);

    /**
     * 테스트 또는 초기 구성에 사용할 noop(아무 동작 없음) 구현체를 반환합니다.
     *
     * @return 저장을 무시하고 항상 빈 Optional을 반환하는 noop 구현체
     */
    static AsyncResultStorePort noop() {
        return new AsyncResultStorePort() {
            @Override
            public void registerPending(final String traceId, final long timeoutMs) {
                // noop: 아무 동작 없음
            }

            @Override
            public void markCompleted(final String traceId, final UiCommandReply reply) {
                // noop: 아무 동작 없음
            }

            @Override
            public void markTimeout(final String traceId) {
                // noop: 아무 동작 없음
            }

            @Override
            public Optional<AsyncResultEntry> getWithStatus(final String traceId) {
                return Optional.empty();
            }
        };
    }
}
