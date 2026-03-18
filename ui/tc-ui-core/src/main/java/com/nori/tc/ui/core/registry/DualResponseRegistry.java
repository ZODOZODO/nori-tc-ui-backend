package com.nori.tc.ui.core.registry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.nori.tc.ui.core.port.redis.DualResponseRedisPort;
import com.nori.tc.ui.domain.task.UiTaskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * eqp_create / eqp_update / eqp_delete 요청의 양방향 응답 수집 레지스트리입니다.
 *
 * <p>설계 목표:</p>
 * <ul>
 *   <li>로컬 메모리만 사용하던 단일 인스턴스 제약을 제거합니다.</li>
 *   <li>응답 상태는 Redis에 저장하고, 로컬에는 현재 인스턴스의 대기 Future만 유지합니다.</li>
 *   <li>Redis Pub/Sub 완료 신호를 받아 해당 traceId Future를 완료합니다.</li>
 * </ul>
 */
@Component
public class DualResponseRegistry {

    private static final Logger log = LoggerFactory.getLogger(DualResponseRegistry.class);

    /** Gateway가 tc.ui.commands에 발행하는 응답 source 값 */
    public static final String SOURCE_GATEWAY = "TC-COMM-GATEWAY";

    /** Business Core가 tc.ui.commands에 발행하는 응답 source 값 */
    public static final String SOURCE_BUSINESS = "TC-BUSINESS-CORE";

    /**
     * 로컬 인스턴스 대기 Future 맵입니다.
     *
     * <p>분산 상태는 Redis에 저장하므로, 이 맵은 "현재 인스턴스가 처리 중인 HTTP 요청"의
     * 응답 대기 핸들만 보관합니다.</p>
     */
    private final ConcurrentHashMap<String, PendingFutureTracker> pendingFutures =
            new ConcurrentHashMap<>();

    private final DualResponseRedisPort dualResponseRedisPort;
    private final MeterRegistry meterRegistry;
    private final Counter registeredCounter;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * @param dualResponseRedisPort DualResponse Redis 상태 저장 포트
     */
    public DualResponseRegistry(final DualResponseRedisPort dualResponseRedisPort) {
        this(dualResponseRedisPort, null);
    }

    /**
     * Micrometer 레지스트리를 포함한 생성자입니다.
     *
     * <p>운영 환경에서는 meterRegistry가 주입되어 메트릭을 기록하고,
     * 단위 테스트에서는 null을 허용하여 메트릭 의존 없이 로직만 검증할 수 있습니다.</p>
     *
     * @param dualResponseRedisPort DualResponse Redis 상태 저장 포트
     * @param meterRegistry Micrometer 메트릭 레지스트리 (없으면 null)
     */
    @Autowired
    public DualResponseRegistry(
            final DualResponseRedisPort dualResponseRedisPort,
            @Nullable final MeterRegistry meterRegistry
    ) {
        this.dualResponseRedisPort = Objects.requireNonNull(dualResponseRedisPort,
                "dualResponseRedisPort is null");
        this.meterRegistry = meterRegistry;
        this.registeredCounter = meterRegistry == null
                ? null
                : meterRegistry.counter("dual_response.registered");
    }

    /**
     * traceId 기준 DualResponse 대기를 등록하고 Future를 반환합니다.
     *
     * <p>처리 순서:</p>
     * <ol>
     *   <li>Redis에 traceId 대기 상태를 먼저 등록합니다.</li>
     *   <li>로컬 pendingFutures에 CompletableFuture를 등록합니다.</li>
     *   <li>timeoutMs 기반 타임아웃을 적용합니다.</li>
     * </ol>
     *
     * @param traceId 추적 ID
     * @param timeoutMs 타임아웃(ms)
     * @return 최종 결과 Future
     */
    public CompletableFuture<UiDualTaskFinalResult> register(final String traceId, final long timeoutMs) {
        Objects.requireNonNull(traceId, "traceId is null");

        dualResponseRedisPort.register(traceId, timeoutMs);

        final PendingFutureTracker tracker = new PendingFutureTracker();
        pendingFutures.put(traceId, tracker);
        incrementRegisteredCounter();

        log.debug("DualResponse 등록 완료. traceId={}, timeoutMs={}, pendingCount={}",
                traceId, timeoutMs, pendingFutures.size());

        final CompletableFuture<UiDualTaskFinalResult> timedFuture =
                tracker.future().orTimeout(timeoutMs, TimeUnit.MILLISECONDS);

        timedFuture.whenComplete((result, throwable) -> {
            try {
                handleCompletion(traceId, timeoutMs, tracker, result, throwable);
            } finally {
                // 완료/타임아웃/취소/예외 모든 경로에서 반드시 정리합니다.
                pendingFutures.remove(traceId, tracker);
            }
        });

        return timedFuture;
    }

    /**
     * 수신된 응답을 Redis 상태에 기록합니다.
     *
     * <p>실제 최종 완료 판단은 Redis 상태(양쪽 응답 존재) 기준으로 수행됩니다.</p>
     *
     * @param traceId 추적 ID
     * @param source 응답 source
     * @param result 응답 결과
     */
    public void record(final String traceId, final String source, final UiTaskResult result) {
        Objects.requireNonNull(traceId, "traceId is null");
        Objects.requireNonNull(source, "source is null");
        Objects.requireNonNull(result, "result is null");

        dualResponseRedisPort.record(traceId, source, result);
    }

    /**
     * 발행 실패 등 비정상 흐름에서 traceId 대기를 강제 취소합니다.
     *
     * <p>HTTP 요청 스레드에서 즉시 실패 응답을 내려야 하는 경우 사용합니다.</p>
     *
     * @param traceId 취소할 traceId
     */
    public void cancel(final String traceId) {
        Objects.requireNonNull(traceId, "traceId is null");

        dualResponseRedisPort.cancel(traceId);

        final PendingFutureTracker tracker = pendingFutures.remove(traceId);
        if (tracker != null && tracker.tryCancel()) {
            recordCompletionMetrics("cancelled", tracker);
            log.debug("DualResponse 로컬 Future 취소 완료. traceId={}", traceId);
        }
    }

    /**
     * Redis Pub/Sub 완료 신호를 받아 로컬 Future를 완료합니다.
     *
     * <p>다른 인스턴스에서 record()가 완료되어 완료 채널에 traceId가 발행될 수 있으므로,
     * 현재 인스턴스에 해당 traceId Future가 있으면 Redis에서 최종 결과를 조회해 완료합니다.</p>
     *
     * @param traceId 완료 신호를 받은 traceId
     */
    public void completeFromRedis(final String traceId) {
        Objects.requireNonNull(traceId, "traceId is null");

        final PendingFutureTracker tracker = pendingFutures.get(traceId);
        if (tracker == null) {
            if (log.isDebugEnabled()) {
                log.debug("완료 신호 수신했지만 로컬 대기 Future 없음. traceId={}", traceId);
            }
            return;
        }

        final Optional<UiDualTaskFinalResult> resultOpt = dualResponseRedisPort.getResult(traceId);
        if (resultOpt.isEmpty()) {
            log.warn("완료 신호 수신했지만 Redis 결과 미완성. traceId={}", traceId);
            return;
        }

        if (tracker.tryComplete(resultOpt.get())) {
            log.debug("Redis 완료 신호로 Future 완료. traceId={}", traceId);
        } else if (log.isDebugEnabled()) {
            log.debug("Redis 완료 신호 무시 - 이미 완료된 traceId. traceId={}", traceId);
        }
    }

    /**
     * 현재 인스턴스 기준 대기 Future 개수를 반환합니다.
     *
     * @return pending future 개수
     */
    public int pendingCount() {
        return pendingFutures.size();
    }

    /**
     * 특정 traceId가 현재 인스턴스에서 대기 중인지 반환합니다.
     *
     * @param traceId 조회할 traceId
     * @return 대기 중이면 true
     */
    public boolean isPending(final String traceId) {
        Objects.requireNonNull(traceId, "traceId is null");
        return pendingFutures.containsKey(traceId);
    }

    /**
     * Future 완료 콜백 공통 처리 로직입니다.
     *
     * <p>중복 완료 경쟁을 방지하기 위해 tracker의 완료 플래그를 기준으로
     * 부수효과(타임아웃 정리 로그 등)를 1회만 수행합니다.</p>
     */
    private void handleCompletion(
            final String traceId,
            final long timeoutMs,
            final PendingFutureTracker tracker,
            final UiDualTaskFinalResult result,
            final Throwable throwable
    ) {
        if (throwable instanceof TimeoutException) {
            if (tracker.markTerminalIfFirst()) {
                // 타임아웃이 발생하면 Redis 상태도 정리하여 고아 키 누적을 방지합니다.
                dualResponseRedisPort.cancel(traceId);
                recordCompletionMetrics("timeout", tracker);
                log.warn("DualResponse 타임아웃. traceId={}, timeoutMs={}", traceId, timeoutMs);
            }
            return;
        }

        if (throwable instanceof CancellationException) {
            if (tracker.markTerminalIfFirst()) {
                recordCompletionMetrics("cancelled", tracker);
                log.warn("DualResponse 취소됨. traceId={}", traceId);
            }
            return;
        }

        if (throwable != null) {
            if (tracker.markTerminalIfFirst()) {
                recordCompletionMetrics("cancelled", tracker);
                log.error("DualResponse 비정상 종료. traceId={}, error={}",
                        traceId, throwable.getMessage(), throwable);
            }
            return;
        }

        if (tracker.markTerminalIfFirst()) {
            recordCompletionMetrics("success", tracker);
            log.debug("DualResponse 완료. traceId={}, success={}", traceId, result.success());
        }
    }

    /**
     * 등록 카운터 메트릭을 증가시킵니다.
     */
    private void incrementRegisteredCounter() {
        if (registeredCounter == null) {
            return;
        }
        registeredCounter.increment();
    }

    /**
     * 완료 상태별 메트릭(카운터/타이머)을 기록합니다.
     *
     * <p>요구 메트릭:</p>
     * <ul>
     *   <li>{@code dual_response.completed} counter (tag: status)</li>
     *   <li>{@code dual_response.duration} timer</li>
     * </ul>
     *
     * @param status 완료 상태 태그 값
     * @param tracker 완료 트래커(등록 시각 포함)
     */
    private void recordCompletionMetrics(final String status, final PendingFutureTracker tracker) {
        if (meterRegistry == null) {
            return;
        }

        meterRegistry.counter("dual_response.completed", "status", safeStatusTag(status)).increment();
        Timer.builder("dual_response.duration")
                .tag("status", safeStatusTag(status))
                .register(meterRegistry)
                .record(Math.max(0L, System.nanoTime() - tracker.registeredAtNanos()), TimeUnit.NANOSECONDS);
    }

    /**
     * status 태그를 null-safe 문자열로 정규화합니다.
     *
     * @param rawStatus 원본 상태 값
     * @return 메트릭 태그용 상태 문자열
     */
    private static String safeStatusTag(final String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return "unknown";
        }
        return rawStatus.trim();
    }

    /**
     * traceId 단위 완료 경쟁을 제어하는 로컬 트래커입니다.
     */
    private static final class PendingFutureTracker {

        private final CompletableFuture<UiDualTaskFinalResult> future = new CompletableFuture<>();
        private final AtomicBoolean terminal = new AtomicBoolean(false);
        private final long registeredAtNanos = System.nanoTime();

        private CompletableFuture<UiDualTaskFinalResult> future() {
            return future;
        }

        /**
         * 등록 시각(nanoTime)을 반환합니다.
         *
         * @return Future 등록 시각
         */
        private long registeredAtNanos() {
            return registeredAtNanos;
        }

        /**
         * 성공 결과 완료를 시도합니다.
         *
         * @param result 최종 결과
         * @return 이번 호출로 최초 완료되었으면 true
         */
        private boolean tryComplete(final UiDualTaskFinalResult result) {
            if (!terminal.compareAndSet(false, true)) {
                return false;
            }
            return future.complete(result);
        }

        /**
         * 취소 완료를 시도합니다.
         *
         * @return 이번 호출로 최초 취소되었으면 true
         */
        private boolean tryCancel() {
            if (!terminal.compareAndSet(false, true)) {
                return false;
            }
            return future.cancel(true);
        }

        /**
         * 타임아웃/예외 콜백에서 종료 마크를 1회만 기록합니다.
         *
         * @return 이번 호출이 최초 종료 기록이면 true
         */
        private boolean markTerminalIfFirst() {
            return terminal.compareAndSet(false, true);
        }
    }
}
