package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.deploy.DeployTraceCompletion;
import com.nori.tc.ui.core.model.UiCommandReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * deploy step traceId -> reply 집계를 관리합니다.
 */
@Component
public class DeployTraceRegistry {

    public static final String DEPLOY_TRACE_PREFIX = "DEPLOY-";

    private static final Logger log = LoggerFactory.getLogger(DeployTraceRegistry.class);

    private final ConcurrentHashMap<String, PendingTrace> pendingTraces = new ConcurrentHashMap<>();

    /**
     * deploy orchestrator가 관리하는 traceId인지 반환합니다.
     *
     * @param traceId 확인 대상 traceId
     * @return deploy traceId 여부
     */
    public boolean isDeployTrace(final String traceId) {
        return traceId != null && traceId.startsWith(DEPLOY_TRACE_PREFIX);
    }

    /**
     * step reply 대기를 등록합니다.
     *
     * @param traceId traceId
     * @param expectedSources 기대 source 집합
     * @return 완료 future
     */
    public CompletableFuture<DeployTraceCompletion> register(
            final String traceId,
            final Set<String> expectedSources
    ) {
        Objects.requireNonNull(traceId, "traceId is null");
        final Set<String> normalizedSources = Set.copyOf(Objects.requireNonNull(expectedSources, "expectedSources is null"));
        final PendingTrace pendingTrace = new PendingTrace(traceId, normalizedSources);
        pendingTraces.put(traceId, pendingTrace);
        return pendingTrace.future();
    }

    /**
     * 수신된 reply를 반영합니다.
     *
     * @param reply 수신 reply
     * @return deploy trace 처리 대상이면 true
     */
    public boolean handle(final UiCommandReply reply) {
        Objects.requireNonNull(reply, "reply is null");

        if (!isDeployTrace(reply.traceId())) {
            return false;
        }

        final PendingTrace pendingTrace = pendingTraces.get(reply.traceId());
        if (pendingTrace == null) {
            log.debug("대기 중이 아닌 deploy trace reply를 무시합니다. traceId={}, source={}, eventType={}",
                    reply.traceId(), reply.source(), reply.eventType());
            return true;
        }

        pendingTrace.record(reply);
        return true;
    }

    /**
     * timeout/취소 시 trace 대기를 제거합니다.
     *
     * @param traceId traceId
     */
    public void cancel(final String traceId) {
        final PendingTrace pendingTrace = pendingTraces.remove(traceId);
        if (pendingTrace != null) {
            pendingTrace.future().completeExceptionally(new CancellationException("trace canceled: " + traceId));
        }
    }

    private final class PendingTrace {

        private final String traceId;
        private final Set<String> expectedSources;
        private final Map<String, UiCommandReply> repliesBySource;
        private final CompletableFuture<DeployTraceCompletion> future;

        private PendingTrace(
                final String traceId,
                final Set<String> expectedSources
        ) {
            this.traceId = traceId;
            this.expectedSources = expectedSources;
            this.repliesBySource = new LinkedHashMap<>();
            this.future = new CompletableFuture<>();
        }

        private synchronized void record(final UiCommandReply reply) {
            if (!expectedSources.contains(reply.source())) {
                log.debug("기대하지 않은 source reply를 무시합니다. traceId={}, source={}, expectedSources={}",
                        traceId, reply.source(), expectedSources);
                return;
            }
            if (repliesBySource.containsKey(reply.source())) {
                log.debug("중복 source reply를 무시합니다. traceId={}, source={}", traceId, reply.source());
                return;
            }

            repliesBySource.put(reply.source(), reply);
            if (!expectedSources.stream().allMatch(repliesBySource::containsKey)) {
                return;
            }

            pendingTraces.remove(traceId, this);
            future.complete(new DeployTraceCompletion(
                    traceId,
                    expectedSources,
                    new ArrayList<>(repliesBySource.values())
            ));
        }

        private CompletableFuture<DeployTraceCompletion> future() {
            return future;
        }
    }
}
