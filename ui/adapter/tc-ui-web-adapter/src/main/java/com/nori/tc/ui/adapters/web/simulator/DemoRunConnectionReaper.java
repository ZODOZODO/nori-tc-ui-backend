package com.nori.tc.ui.adapters.web.simulator;

import com.nori.tc.ui.adapters.web.config.UiDualRequestProperties;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.service.DemoRunConnectionGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.Objects;

/**
 * 추적 중인 데모-run 의 orchestrator 상태를 주기 폴링해 종료(TERMINAL/FAILED)·미존재(404)면 이 run 이 연결한
 * 설비(레지스트리 captured-set)에 EQP_END 를 발행하고 추적을 해제하는 서버측 reaper.
 *
 * <p>자연완료(TERMINAL)·실패(FAILED)는 사용자 stop 이 없는 종료 경로라 facade 가 정리하지 못한다. 이를 모달/
 * 브라우저 생존과 무관하게 서버에서 정리한다. CANCELED 는 stop 전담(facade)이라 제외한다 — reaper 가 또 잡으면
 * stop 과 race 로 이중 END 가 되므로 제외가 곧 race 회피다. EQP_END 대상은 반드시 captured-set 으로 좁힌다 —
 * getRun 전체 target 을 쓰면 사전 연결 설비까지 END 되어 "테스트 전 상태 원복"을 위반한다.</p>
 */
public final class DemoRunConnectionReaper {

    private static final Logger log = LoggerFactory.getLogger(DemoRunConnectionReaper.class);

    private final SimulatorOrchestratorClient client;
    private final DemoRunConnectionGate connectionGate;
    private final DemoRunConnectionRegistry runRegistry;
    private final UiDualRequestProperties dualRequestProperties;

    public DemoRunConnectionReaper(
            final SimulatorOrchestratorClient client,
            final DemoRunConnectionGate connectionGate,
            final DemoRunConnectionRegistry runRegistry,
            final UiDualRequestProperties dualRequestProperties
    ) {
        this.client = Objects.requireNonNull(client, "client is null");
        this.connectionGate = Objects.requireNonNull(connectionGate, "connectionGate is null");
        this.runRegistry = Objects.requireNonNull(runRegistry, "runRegistry is null");
        this.dualRequestProperties = Objects.requireNonNull(dualRequestProperties, "dualRequestProperties is null");
    }

    @Scheduled(fixedDelayString = "${tc.ui.simulator.reaper-interval-ms:5000}")
    public void reapTerminatedRuns() {
        for (final String runId : runRegistry.trackedRunIds()) {
            try {
                final SimulatorRunResponse run = client.getRun(runId);
                if (isTerminal(run.state())) {
                    releaseAndUntrack(runId);
                }
                // 그 외 상태(RUNNING 등) → 추적 유지(다음 tick 재확인).
            } catch (SimulatorRunNotFoundException notFound) {
                // 404: orchestrator 가 run 을 더는 보유하지 않음(재시작 등) → 종료로 간주해 원복.
                releaseAndUntrack(runId);
            } catch (IllegalStateException serverError) {
                // 5xx: 일시 오류 → 추적 유지·다음 tick 재시도(거짓 원복 금지).
                log.warn("reaper getRun 5xx — 추적 유지. runId={}", runId, serverError);
            } catch (UiBadRequestException ioOrOther4xx) {
                // IO/타임아웃/기타 4xx: 일시 오류 → 추적 유지·다음 tick 재시도(거짓 원복 금지).
                log.warn("reaper getRun 일시오류 — 추적 유지. runId={}", runId, ioOrOther4xx);
            }
        }
    }

    /** reaper 가 "종료"로 보는 orchestrator state — 정확히 TERMINAL/FAILED. CANCELED 는 stop 전담이라 제외(C2). */
    private static boolean isTerminal(final String state) {
        return "TERMINAL".equals(state) || "FAILED".equals(state);
    }

    private void releaseAndUntrack(final String runId) {
        // R4: 원자적 claim 한 쪽만 END — stop 경로와 동일 메커니즘으로 이중 END 를 막는다.
        runRegistry.claim(runId).ifPresent(captured ->
                connectionGate.releaseActiveTargets(List.copyOf(captured),
                        dualRequestProperties.getDualRequestTimeoutMs()));
    }
}
