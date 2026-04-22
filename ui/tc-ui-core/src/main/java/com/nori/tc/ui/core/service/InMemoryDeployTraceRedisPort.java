package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.deploy.DeployTraceCompletion;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.port.redis.DeployTraceRedisPort;
import com.nori.tc.ui.core.registry.DeployTraceRedisLookupResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Redis 미사용 환경과 단위 테스트를 위한 인메모리 deploy trace 저장 구현입니다.
 */
final class InMemoryDeployTraceRedisPort implements DeployTraceRedisPort {

    private final ConcurrentHashMap<String, PendingTrace> traces = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<String>> completionListeners = new CopyOnWriteArrayList<>();

    @Override
    public void register(final String traceId, final Set<String> expectedSources, final long timeoutMs) {
        Objects.requireNonNull(traceId, "traceId is null");
        traces.put(traceId, new PendingTrace(traceId, Set.copyOf(expectedSources)));
    }

    @Override
    public void record(final UiCommandReply reply) {
        Objects.requireNonNull(reply, "reply is null");
        final PendingTrace pendingTrace = traces.get(reply.traceId());
        if (pendingTrace == null) {
            return;
        }
        if (pendingTrace.record(reply)) {
            completionListeners.forEach(listener -> listener.accept(reply.traceId()));
        }
    }

    @Override
    public void cancel(final String traceId) {
        if (traceId == null) {
            return;
        }
        traces.remove(traceId);
    }

    @Override
    public boolean isRegistered(final String traceId) {
        return traceId != null && traces.containsKey(traceId);
    }

    @Override
    public DeployTraceRedisLookupResult getCompletion(final String traceId) {
        final PendingTrace pendingTrace = traces.get(traceId);
        if (pendingTrace == null) {
            return DeployTraceRedisLookupResult.pending();
        }
        return pendingTrace.toLookupResult();
    }

    @Override
    public void subscribe(final Consumer<String> completionListener) {
        if (completionListener != null) {
            completionListeners.add(completionListener);
        }
    }

    private static final class PendingTrace {

        private final String traceId;
        private final Set<String> expectedSources;
        private final Map<String, UiCommandReply> repliesBySource = new LinkedHashMap<>();

        private PendingTrace(final String traceId, final Set<String> expectedSources) {
            this.traceId = traceId;
            this.expectedSources = expectedSources;
        }

        private synchronized boolean record(final UiCommandReply reply) {
            if (!expectedSources.contains(reply.source())) {
                return false;
            }
            repliesBySource.putIfAbsent(reply.source(), reply);
            return expectedSources.stream().allMatch(repliesBySource::containsKey);
        }

        private synchronized DeployTraceRedisLookupResult toLookupResult() {
            if (!expectedSources.stream().allMatch(repliesBySource::containsKey)) {
                return DeployTraceRedisLookupResult.pending();
            }
            return DeployTraceRedisLookupResult.completed(new DeployTraceCompletion(
                    traceId,
                    expectedSources,
                    new ArrayList<>(repliesBySource.values())
            ));
        }
    }
}
