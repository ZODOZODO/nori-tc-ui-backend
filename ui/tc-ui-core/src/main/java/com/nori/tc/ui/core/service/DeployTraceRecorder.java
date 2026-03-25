package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.deploy.DeployTraceCompletion;
import com.nori.tc.ui.core.model.UiCommandReply;
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

    public boolean handle(final UiCommandReply reply) {
        return deployTraceRegistry.handle(reply);
    }
}
