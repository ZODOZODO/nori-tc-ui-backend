package com.nori.tc.ui.core.port.redis;

import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.registry.DeployTraceRedisLookupResult;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Deploy trace reply 수집의 분산 상태 저장 포트입니다.
 *
 * <p>Deploy step reply는 target executor thread와 Kafka consumer가 다른 인스턴스에 있을 수 있으므로,
 * trace 상태와 source dedupe를 durable store에 기록해야 합니다.</p>
 */
public interface DeployTraceRedisPort {

    /**
     * traceId 대기 상태를 등록합니다.
     *
     * @param traceId deploy trace ID
     * @param expectedSources 기대 source 집합
     * @param timeoutMs trace TTL(ms)
     */
    void register(String traceId, Set<String> expectedSources, long timeoutMs);

    /**
     * 수신 reply를 기록하고 source dedupe를 수행합니다.
     *
     * @param reply 수신 reply
     */
    void record(UiCommandReply reply);

    /**
     * traceId 상태를 정리합니다.
     *
     * @param traceId 정리 대상 traceId
     */
    void cancel(String traceId);

    /**
     * traceId 기준 분산 저장소 결과를 조회합니다.
     *
     * @param traceId 조회 대상 traceId
     * @return 조회 결과
     */
    DeployTraceRedisLookupResult getCompletion(String traceId);

    /**
     * 테스트용 인메모리 구현에서 completion callback을 전달할 때 사용합니다.
     *
     * <p>실제 Redis 구현은 Pub/Sub listener가 별도로 존재하므로 기본 no-op 입니다.</p>
     *
     * @param completionListener traceId 완료 콜백
     */
    default void subscribe(final Consumer<String> completionListener) {
        // no-op
    }

    /**
     * 테스트/초기 부팅용 noop 구현체입니다.
     *
     * @return 아무 동작도 하지 않는 구현
     */
    static DeployTraceRedisPort noop() {
        return new DeployTraceRedisPort() {
            @Override
            public void register(final String traceId, final Set<String> expectedSources, final long timeoutMs) {
                // noop
            }

            @Override
            public void record(final UiCommandReply reply) {
                // noop
            }

            @Override
            public void cancel(final String traceId) {
                // noop
            }

            @Override
            public DeployTraceRedisLookupResult getCompletion(final String traceId) {
                return DeployTraceRedisLookupResult.pending();
            }
        };
    }
}
