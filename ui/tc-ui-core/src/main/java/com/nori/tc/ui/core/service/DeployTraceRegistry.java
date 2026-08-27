package com.nori.tc.ui.core.service;
import com.nori.tc.ui.core.deploy.DeployTraceCompletion;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.port.redis.DeployTraceRedisPort;
import com.nori.tc.ui.core.registry.DeployTraceRedisLookupResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * deploy step traceId -> reply 집계를 관리합니다.
 *
 * <p>분산 저장소에 실제 reply 상태를 기록하고, 현재 인스턴스에는 대기 Future만 유지합니다.</p>
 */
@Component
public class DeployTraceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DeployTraceRegistry.class);

    private final ConcurrentHashMap<String, PendingFutureTracker> pendingFutures = new ConcurrentHashMap<>();
    private final DeployTraceRedisPort deployTraceRedisPort;

    public DeployTraceRegistry(final DeployTraceRedisPort deployTraceRedisPort) {
        this.deployTraceRedisPort = Objects.requireNonNull(deployTraceRedisPort,
                "deployTraceRedisPort is null");
        this.deployTraceRedisPort.subscribe(this::completeFromRedis);
    }

    /**
     * deploy/direct scenario orchestrator가 관리하는 traceId인지 반환합니다.
     *
     * @param traceId 확인 대상 traceId
     * @return 관리 대상 traceId 여부
     */
    public boolean isDeployTrace(final String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return false;
        }
        if (pendingFutures.containsKey(traceId)) {
            return true;
        }
        return deployTraceRedisPort.isRegistered(traceId);
    }

    /**
     * step reply 대기를 등록합니다.
     *
     * @param traceId traceId
     * @param expectedSources 기대 source 집합
     * @param timeoutMs TTL(ms)
     * @return 완료 future
     */
    public CompletableFuture<DeployTraceCompletion> register(
            final String traceId,
            final Set<String> expectedSources,
            final long timeoutMs
    ) {
        Objects.requireNonNull(traceId, "traceId is null");
        final Set<String> normalizedSources = Set.copyOf(Objects.requireNonNull(expectedSources, "expectedSources is null"));

        deployTraceRedisPort.register(traceId, normalizedSources, timeoutMs);

        final PendingFutureTracker tracker = new PendingFutureTracker();
        pendingFutures.put(traceId, tracker);

        // 다른 인스턴스가 먼저 reply를 기록한 경우를 대비해 등록 직후 상태를 한 번 조회합니다.
        completeFromRedis(traceId);
        return tracker.future();
    }

    /**
     * 수신된 reply를 분산 저장소에 반영합니다.
     *
     * @param reply 수신 reply
     * @return deploy trace 처리 대상이면 true
     */
    public boolean handle(final UiCommandReply reply) {
        Objects.requireNonNull(reply, "reply is null");

        if (!isDeployTrace(reply.traceId())) {
            return false;
        }

        deployTraceRedisPort.record(reply);
        // 동일 인스턴스 처리 경로에서는 Pub/Sub 없이도 즉시 완료할 수 있게 바로 조회합니다.
        completeFromRedis(reply.traceId());
        return true;
    }

    /**
     * traceId 의 분산 저장소 상태를 <b>블로킹 없이</b> 조회합니다(§13.6).
     *
     * @param traceId 조회 대상 traceId
     * @return 분산 저장소 조회 결과
     */
    public DeployTraceRedisLookupResult completionOf(final String traceId) {
        Objects.requireNonNull(traceId, "traceId is null");
        return deployTraceRedisPort.getCompletion(traceId);
    }

    /**
     * timeout/취소 시 trace 대기를 제거합니다.
     *
     * @param traceId traceId
     */
    public void cancel(final String traceId) {
        deployTraceRedisPort.cancel(traceId);

        final PendingFutureTracker tracker = pendingFutures.remove(traceId);
        if (tracker != null && tracker.tryCancel()) {
            log.debug("deploy trace 대기를 취소했습니다. traceId={}", traceId);
        }
    }

    /**
     * Redis Pub/Sub 완료 신호 또는 로컬 즉시 조회 경로에서 완료를 반영합니다.
     *
     * @param traceId traceId
     */
    public void completeFromRedis(final String traceId) {
        Objects.requireNonNull(traceId, "traceId is null");

        final PendingFutureTracker tracker = pendingFutures.get(traceId);
        if (tracker == null) {
            return;
        }

        final DeployTraceRedisLookupResult lookupResult = deployTraceRedisPort.getCompletion(traceId);
        if (lookupResult.status() == DeployTraceRedisLookupResult.Status.PENDING) {
            return;
        }
        if (lookupResult.status() == DeployTraceRedisLookupResult.Status.EXPIRED) {
            /*
             * 로컬 future 는 여기서 끝내지 않는다 — 이 경로의 대기자(DeployExecutor)는 자기 timeout 으로
             * STEP_TIMEOUT 을 확정한다. 여기서 예외로 깨면 그 timeout 코드가 STEP_EXECUTION_FAILED 로 바뀐다.
             */
            log.debug("deploy trace 등록이 만료되었습니다. traceId={}", traceId);
            return;
        }
        if (lookupResult.status() == DeployTraceRedisLookupResult.Status.INFRA_ERROR) {
            log.error("deploy trace Redis 조회 실패. failureCode=UI_DEPLOY_TRACE_LOOKUP_FAILED, "
                            + "failureCategory=INFRA, failureSeverity=ERROR, traceId={}, error={}",
                    traceId, lookupResult.errorMessage());
            return;
        }

        if (tracker.tryComplete(lookupResult.completion())) {
            pendingFutures.remove(traceId, tracker);
            log.debug("deploy trace 완료. traceId={}", traceId);
        }
    }

    public int pendingCount() {
        return pendingFutures.size();
    }

    private static final class PendingFutureTracker {

        private final CompletableFuture<DeployTraceCompletion> future = new CompletableFuture<>();
        private final AtomicBoolean terminal = new AtomicBoolean(false);

        private CompletableFuture<DeployTraceCompletion> future() {
            return future;
        }

        private boolean tryComplete(final DeployTraceCompletion completion) {
            if (!terminal.compareAndSet(false, true)) {
                return false;
            }
            return future.complete(completion);
        }

        private boolean tryCancel() {
            if (!terminal.compareAndSet(false, true)) {
                return false;
            }
            return future.completeExceptionally(new CancellationException("deploy trace canceled"));
        }
    }
}
