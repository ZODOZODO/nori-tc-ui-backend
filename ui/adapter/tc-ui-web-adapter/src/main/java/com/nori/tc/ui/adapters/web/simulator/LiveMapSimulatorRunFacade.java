package com.nori.tc.ui.adapters.web.simulator;

import com.nori.tc.ui.adapters.web.config.UiDualRequestProperties;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.service.DemoRunConnectionGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 모달 Run 경로 facade(R-1/R-2/R-3) — testType→mode 매핑 + 선택 설비 DB 설정→target 변환 + orchestrator createRun.
 *
 * <p><b>연결-후-MES 배리어(실사용자 절차 모사).</b> "테스트 시작"을 누르면 ① 설비를 먼저 연결하고 ② 연결을 확인한
 * 뒤에만 ③ 테스트 로직(MES)을 돌린다. orchestrator createRun 은 설비를 bind 하되(ACTIVE=server bind, PASSIVE=gateway
 * dial-in) ALL 모드에서는 MES inject 를 보류한다. 그 다음 {@link DemoRunConnectionGate} 로 연결을 확인한다 —
 * ACTIVE 는 EQP_START_REP PASS(=gateway lifecycle APPLIED=실제 dial 성공), PASSIVE 는 connectionState=CONN. 연결이
 * 확인되면 {@code activateUpstream} 으로 보류했던 MES 를 inject 한다(미연결 MES 송신 = 유실 방지). 모드별:</p>
 * <ul>
 *   <li>ALL: createRun(설비 bind, MES 보류) → ACTIVE 연결(EQP_START PASS) + PASSIVE 연결(CONN) 확인 → activateUpstream.</li>
 *   <li>EQUIPMENT_ONLY: createRun(설비 bind) → ACTIVE 만 EQP_START(설비 시나리오는 handshake 시 시작·loop). MES 없음.</li>
 *   <li>UPSTREAM_ONLY: createRun(MES inject·loop) → ACTIVE 면 EQP_START best-effort(실설비 대상, 연결 미보장).</li>
 * </ul>
 * <p>연결/활성화 실패 시 orchestrator run 을 stop(설비 unbind/abort)하고 START 시킨 설비를 해제(EQP_END)한 뒤 예외를
 * 전파한다(조용한 부분 시작 금지). stop 시에도 START 시킨 설비를 best-effort 로 해제한다.</p>
 */
public final class LiveMapSimulatorRunFacade {

    /** 연결-후-MES 배리어 백그라운드 실행 executor 빈 이름(starter 가 이 이름으로 등록). */
    public static final String ACTIVATION_EXECUTOR_BEAN_NAME = "demoRunActivationExecutor";

    private static final Logger log = LoggerFactory.getLogger(LiveMapSimulatorRunFacade.class);

    private static final String MODE_ALL = "ALL";
    private static final String MODE_EQUIPMENT_ONLY = "EQUIPMENT_ONLY";
    private static final String MODE_UPSTREAM_ONLY = "UPSTREAM_ONLY";

    private final SimulatorOrchestratorClient client;
    private final SimulatorModeMapper modeMapper;
    private final SimulatorTargetAssembler targetAssembler;
    private final DemoRunConnectionGate connectionGate;
    private final UiDualRequestProperties dualRequestProperties;
    private final DemoRunConnectionRegistry runRegistry;
    private final Executor activationExecutor;
    private final long connectionConfirmTimeoutMs;

    public LiveMapSimulatorRunFacade(
            final SimulatorOrchestratorClient client,
            final SimulatorModeMapper modeMapper,
            final SimulatorTargetAssembler targetAssembler,
            final DemoRunConnectionGate connectionGate,
            final UiDualRequestProperties dualRequestProperties,
            final DemoRunConnectionRegistry runRegistry,
            final Executor activationExecutor,
            final long connectionConfirmTimeoutMs
    ) {
        this.client = Objects.requireNonNull(client, "client is null");
        this.modeMapper = Objects.requireNonNull(modeMapper, "modeMapper is null");
        this.targetAssembler = Objects.requireNonNull(targetAssembler, "targetAssembler is null");
        this.connectionGate = Objects.requireNonNull(connectionGate, "connectionGate is null");
        this.dualRequestProperties = Objects.requireNonNull(dualRequestProperties, "dualRequestProperties is null");
        this.runRegistry = Objects.requireNonNull(runRegistry, "runRegistry is null");
        this.activationExecutor = Objects.requireNonNull(activationExecutor, "activationExecutor is null");
        this.connectionConfirmTimeoutMs = connectionConfirmTimeoutMs;
    }

    /**
     * 모달 시작 — testType→mode + 선택 설비별 DB 설정→target. 연결-후-MES 배리어를 <b>비동기</b>로 수행한다:
     * orchestrator createRun(설비 bind, ALL 은 MES 보류) 까지만 동기로 처리해 즉시 runId 를 반환하고, 설비 연결확인 +
     * MES 활성화(activateUpstream) + 실패 보상(rollback)은 {@link #activationExecutor} 백그라운드에서 수행한다.
     *
     * <p>이유: 연결확인 gate 는 설비 수에 비례해 시간이 늘어, 대량 설비 시 요청 전체가 외부 HTTP 프록시 타임아웃을
     * 넘겨 504 가 나고 그 시점에 요청이 끊겨 activateUpstream 에 도달하지 못했다(MES 미주입 → 설비 flap). createRun 자체는
     * 저렴하므로 동기로 두고 연결확인+활성화를 백그라운드로 옮긴다. 클라이언트는 {@code getRun} 폴링으로 진행/종료를
     * 관측한다(실패 시 백그라운드가 run 을 중단 → terminal 상태로 표면화). 조용한 부분 시작 금지 invariant 는 보존한다.</p>
     */
    public SimulatorRunResponse startRun(final String testType, final String scenarioId, final List<String> eqpIds) {
        final String mode = modeMapper.toMode(testType);
        if (!MODE_ALL.equals(mode) && !MODE_EQUIPMENT_ONLY.equals(mode) && !MODE_UPSTREAM_ONLY.equals(mode)) {
            // createRun 으로 orphan run 을 만들기 전에 거부한다.
            throw new UiBadRequestException("지원하지 않는 mode입니다: " + mode);
        }
        final List<SimulatorCreateRunRequest.SimulatorTargetRequest> targets = eqpIds.stream()
                .map(targetAssembler::toTarget)
                .toList();

        // 1) orchestrator createRun(동기, 저렴) — 설비 bind(ALL/EQUIPMENT_ONLY) 또는 MES inject(UPSTREAM_ONLY).
        final SimulatorRunResponse response = client.createRun(new SimulatorCreateRunRequest(mode, scenarioId, targets));
        final String runId = response.runId();

        // 2) 연결확인 + MES 활성화는 백그라운드로(요청 즉시 반환). ALL/EQUIPMENT_ONLY 는 markPending 으로 추적을 먼저 열어
        //    백그라운드 commit 전에 stop 이 들어와도 항상 tracked run 을 claim 하게 한다(race-safety, R4).
        switch (mode) {
            case MODE_ALL -> {
                runRegistry.markPending(runId);
                activationExecutor.execute(() -> activateAllInBackground(runId, eqpIds));
            }
            case MODE_EQUIPMENT_ONLY -> {
                runRegistry.markPending(runId);
                activationExecutor.execute(() -> activateEquipmentOnlyInBackground(runId, eqpIds));
            }
            default -> // MODE_UPSTREAM_ONLY — best-effort, 추적 안 함(C6).
                    activationExecutor.execute(() -> activateUpstreamOnlyInBackground(runId, eqpIds));
        }
        return response;
    }

    /**
     * ALL 백그라운드 배리어 — ACTIVE 연결(EQP_START PASS) → PASSIVE 연결(control_state=REMOTE) → activateUpstream(MES).
     * 백그라운드 commit 전에 stop 이 선점(claim)했으면 방금 EQP_START 한 captured 를 직접 해제하고 MES 는 활성화하지
     * 않는다(누수 0). 실패는 호출자로 전파할 수 없으므로 run 을 중단·해제(rollback)해 getRun 폴링에 terminal 로 표면화한다.
     */
    private void activateAllInBackground(final String runId, final List<String> eqpIds) {
        try {
            final Set<String> captured = connectionGate.ensureActiveTargetsConnected(eqpIds, connectionConfirmTimeoutMs);
            if (!runRegistry.commitCaptured(runId, captured)) {
                connectionGate.releaseActiveTargets(List.copyOf(captured), dualRequestProperties.getDualRequestTimeoutMs());
                log.info("데모-run 활성화 취소(stop 선점) — captured self-release. runId={}, captured={}", runId, captured);
                return;
            }
            connectionGate.awaitPassiveTargetsConnected(eqpIds, connectionConfirmTimeoutMs);
            client.activateUpstream(runId);
            log.info("데모-run 활성화 완료(백그라운드). runId={}", runId);
        } catch (RuntimeException activationFailure) {
            log.warn("데모-run 활성화 실패(백그라운드) — run 중단/해제. runId={}", runId, activationFailure);
            rollbackRun(runId, dualRequestProperties.getDualRequestTimeoutMs(), activationFailure);
        }
    }

    /**
     * EQUIPMENT_ONLY 백그라운드 — ACTIVE 만 EQP_START 로 연결 유도(설비 시나리오는 handshake 시 시작·loop). MES 없음.
     * race/실패 처리는 ALL 과 동일.
     */
    private void activateEquipmentOnlyInBackground(final String runId, final List<String> eqpIds) {
        try {
            final Set<String> captured = connectionGate.ensureActiveTargetsConnected(eqpIds, connectionConfirmTimeoutMs);
            if (!runRegistry.commitCaptured(runId, captured)) {
                connectionGate.releaseActiveTargets(List.copyOf(captured), dualRequestProperties.getDualRequestTimeoutMs());
                log.info("데모-run 활성화 취소(stop 선점) — captured self-release. runId={}, captured={}", runId, captured);
                return;
            }
            log.info("데모-run(EQUIPMENT_ONLY) 연결 완료(백그라운드). runId={}", runId);
        } catch (RuntimeException activationFailure) {
            log.warn("데모-run(EQUIPMENT_ONLY) 연결 실패(백그라운드) — run 중단/해제. runId={}", runId, activationFailure);
            rollbackRun(runId, dualRequestProperties.getDualRequestTimeoutMs(), activationFailure);
        }
    }

    /**
     * UPSTREAM_ONLY 백그라운드 — MES 는 createRun 에서 이미 주입·loop. ACTIVE 면 EQP_START 만 best-effort(실설비 대상,
     * 연결 미보장)라 추적/rollback 하지 않는다(C6). 실패해도 run 은 유지하고 로그만 남긴다.
     */
    private void activateUpstreamOnlyInBackground(final String runId, final List<String> eqpIds) {
        try {
            connectionGate.startActiveTargetsBestEffort(eqpIds, dualRequestProperties.getDualRequestTimeoutMs());
            log.info("데모-run(UPSTREAM_ONLY) best-effort 시작 완료(백그라운드). runId={}", runId);
        } catch (RuntimeException bestEffortFailure) {
            log.warn("데모-run(UPSTREAM_ONLY) best-effort 시작 처리 실패(무시). runId={}", runId, bestEffortFailure);
        }
    }

    /**
     * 시작 실패 보상 — orchestrator run 중단(설비 unbind/abort) + 이 run 이 START 시킨 ACTIVE 설비만 해제(EQP_END).
     * 해제 대상은 레지스트리에서 원자적 claim 한 captured-set 으로 좁힌다 — ensureActive 성공 후 awaitPassive/
     * activateUpstream 이 실패한 경로의 누수를 막으면서 사전 연결 설비는 건드리지 않는다(R5). ensureActive 자체가
     * throw 한 경우엔 추적 엔트리가 없어 claim 이 빈 결과라 END 0건(내부 releaseIssued 가 이미 보상). 보상 중 오류는
     * 원인 예외에 suppressed 로 첨부하고 삼키지 않는다(원인 전파 보존).
     */
    private void rollbackRun(final String runId, final long timeoutMs, final RuntimeException cause) {
        try {
            client.stopRun(runId);
        } catch (RuntimeException stopFailure) {
            cause.addSuppressed(stopFailure);
        }
        // claim 은 추적을 항상 소진(reaper 이중 처리 차단)하되, captured 가 비면(markPending 만 됐고 ensureActive 가
        // 연결한 설비 없음 — gate 가 자체 보상 release 함) EQP_END 를 발행하지 않는다(불필요 호출 회피).
        runRegistry.claim(runId)
                .filter(captured -> !captured.isEmpty())
                .ifPresent(captured -> {
                    try {
                        connectionGate.releaseActiveTargets(List.copyOf(captured), timeoutMs);
                    } catch (RuntimeException releaseFailure) {
                        cause.addSuppressed(releaseFailure);
                    }
                });
    }

    public SimulatorRunResponse getRun(final String runId) {
        return client.getRun(runId);
    }

    public SimulatorRunResponse stopRun(final String runId) {
        final long timeoutMs = dualRequestProperties.getDualRequestTimeoutMs();
        // 추적된 run(ALL·EQUIPMENT_ONLY)은 원자적 claim 한 captured-set 으로만 해제 → 사전 연결 설비 미손상(R5),
        // reaper 와의 이중 END 차단(R4: claim 한 쪽만 END).
        final Optional<Set<String>> captured = runRegistry.claim(runId);
        if (captured.isPresent()) {
            final SimulatorRunResponse response = client.stopRun(runId);
            connectionGate.releaseActiveTargets(List.copyOf(captured.get()), timeoutMs);
            return response;
        }
        // 미추적 run(UPSTREAM_ONLY 등) — 현행 현재상태 재해석 동작 보존(no-op 로 바꾸지 않음, R5-주의).
        // run 의 대상 eqpId 는 stop 전에 확보한다(orchestrator Run 의 target 목록).
        final List<String> eqpIds = client.getRun(runId).targets().stream()
                .map(SimulatorRunResponse.SimulatorTargetResponse::eqpId)
                .toList();
        final SimulatorRunResponse response = client.stopRun(runId);
        // best-effort: run 이 START 시킨 ACTIVE 설비 연결을 해제(EQP_END)한다. 실패해도 stop 응답은 그대로 반환.
        connectionGate.releaseActiveTargets(eqpIds, timeoutMs);
        return response;
    }

    /**
     * 설비-단위 종료 — orchestrator 의 해당 target 만 중단(같은 Run 의 다른 target 은 계속)한다. 연결 해제(EQP_END)는
     * <b>이 run 이 직접 연결(EQP_START)한 설비에만</b> 한다(captured-set 멤버십). run 시작 전 이미 연결돼 있던 설비나
     * PASSIVE 설비는 captured-set 에 없어 release=false → 연결을 유지한다(run 이 빌린 연결만 반납, 사전 연결은 보존 —
     * stop/rollback/reaper 의 R5 원복 규칙과 동일).
     *
     * <p>이 종료로 <b>run 전체가 terminal</b> 이 되면(마지막 RUNNING target → orchestrator 가 run CANCELED) reaper 는
     * CANCELED 를 건너뛰므로(stop 전담, race 회피) 여기서 남은 captured-set 전체를 END 하고 추적을 원자적으로 소진한다 —
     * 그래야 자연완료했던 captured 설비의 연결과 registry 엔트리가 누수되지 않는다. 방금 종료한 설비도 captured 면 이
     * 집합에 포함돼 함께 END 된다(이중 END 없음). run 이 계속 활성이면 방금 종료한 설비만 부분 해제·END 한다.</p>
     */
    public SimulatorRunResponse stopTarget(final String runId, final String eqpId) {
        final long timeoutMs = dualRequestProperties.getDualRequestTimeoutMs();
        final SimulatorRunResponse response = client.stopTarget(runId, eqpId);
        if (isRunTerminal(response.state())) {
            runRegistry.claim(runId).ifPresent(captured ->
                    connectionGate.releaseActiveTargets(List.copyOf(captured), timeoutMs));
        } else if (runRegistry.release(runId, eqpId)) {
            connectionGate.releaseActiveTargets(List.of(eqpId), timeoutMs);
        }
        return response;
    }

    private static boolean isRunTerminal(final String state) {
        return "TERMINAL".equals(state) || "CANCELED".equals(state) || "FAILED".equals(state);
    }

    public List<SimulatorScenarioCatalogItem> getScenarios(final String mode) {
        return client.getScenarios(mode);
    }

    /** 시나리오 마크다운 업로드(R-6) — orchestrator 가 검증/적재. 409/400 은 client 가 Ui 예외로 매핑. */
    public SimulatorScenarioCatalogItem uploadScenario(final String sourceText) {
        return client.uploadScenario(sourceText);
    }

    /** 시나리오 삭제(R-6) — 시드 보호(409)/미존재(404)는 client 가 Ui 예외로 매핑. */
    public void deleteScenario(final String scenarioId) {
        client.deleteScenario(scenarioId);
    }
}
