package com.nori.tc.ui.core.port.redis;

import com.nori.tc.ui.core.registry.UiDualTaskFinalResult;
import com.nori.tc.ui.domain.task.UiTaskResult;

import java.util.Optional;

/**
 * DualResponse 분산 상태 저장 포트입니다.
 *
 * <p>역할:</p>
 * <p>eqp_create/update/delete 요청의 Gateway/Business 양방향 응답 상태를
 * Redis에 저장하고 조회합니다. 단일 JVM 메모리 의존을 제거하여
 * 다중 인스턴스 환경에서도 동일 traceId를 기준으로 결과를 수집할 수 있게 합니다.</p>
 */
public interface DualResponseRedisPort {

    /**
     * traceId 기준 DualResponse 대기 상태를 Redis에 등록합니다.
     *
     * @param traceId 등록할 추적 ID
     * @param timeoutMs DualResponse 대기 타임아웃(ms)
     */
    void register(String traceId, long timeoutMs);

    /**
     * 수신된 응답을 Redis 상태에 기록합니다.
     *
     * @param traceId 추적 ID
     * @param source 응답 출처 (TC-COMM-GATEWAY 또는 TC-BUSINESS-CORE)
     * @param result 기록할 응답 결과
     */
    void record(String traceId, String source, UiTaskResult result);

    /**
     * traceId의 Redis 상태를 취소/정리합니다.
     *
     * @param traceId 정리할 추적 ID
     */
    void cancel(String traceId);

    /**
     * Redis에 저장된 양방향 응답을 읽어 최종 결과를 반환합니다.
     *
     * @param traceId 조회할 추적 ID
     * @return 양쪽 응답이 모두 존재하면 최종 결과, 아니면 빈 Optional
     */
    Optional<UiDualTaskFinalResult> getResult(String traceId);

    /**
     * 테스트/초기 부팅용 noop 구현체입니다.
     *
     * @return 아무 동작도 하지 않는 포트 구현
     */
    static DualResponseRedisPort noop() {
        return new DualResponseRedisPort() {
            @Override
            public void register(final String traceId, final long timeoutMs) {
                // noop
            }

            @Override
            public void record(final String traceId, final String source, final UiTaskResult result) {
                // noop
            }

            @Override
            public void cancel(final String traceId) {
                // noop
            }

            @Override
            public Optional<UiDualTaskFinalResult> getResult(final String traceId) {
                return Optional.empty();
            }
        };
    }
}
