package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.deploy.DeployTraceCompletion;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.registry.DeployTraceRedisLookupResult;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Deploy trace register/cancel/handle 책임을 분리합니다.
 */
@Service
public class DeployTraceRecorder {

    private final DeployTraceRegistry deployTraceRegistry;

    public DeployTraceRecorder(final DeployTraceRegistry deployTraceRegistry) {
        this.deployTraceRegistry = Objects.requireNonNull(deployTraceRegistry, "deployTraceRegistry is null");
    }

    public CompletableFuture<DeployTraceCompletion> register(
            final String traceId,
            final Set<String> expectedSources,
            final long timeoutMs
    ) {
        return deployTraceRegistry.register(traceId, expectedSources, timeoutMs);
    }

    public void cancel(final String traceId) {
        deployTraceRegistry.cancel(traceId);
    }

    /**
     * traceId 의 분산 저장소 상태를 <b>블로킹 없이</b> 조회합니다(§13.6).
     *
     * <p>{@link #register} 가 돌려주는 future 는 이 인스턴스 안에만 있습니다. 조정 원장 아래에서
     * 도는 실행은 인계 Pod 도 같은 결론에 도달해야 하므로 future 대신 이 조회를 씁니다.</p>
     *
     * @param traceId 조회 대상 traceId
     * @return 분산 저장소 조회 결과
     */
    public DeployTraceRedisLookupResult completionOf(final String traceId) {
        return deployTraceRegistry.completionOf(traceId);
    }

    public boolean handle(final UiCommandReply reply) {
        return deployTraceRegistry.handle(reply);
    }
}
