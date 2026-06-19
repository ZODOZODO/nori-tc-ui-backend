package com.nori.tc.ui.adapters.web.simulator;

import com.nori.tc.ui.adapters.web.config.UiDualRequestProperties;
import com.nori.tc.ui.adapters.web.config.UiSimulatorProperties;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.service.DemoRunConnectionGate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 모달 Run 경로 facade(R-1/R-2/R-3) — testType→mode 매핑 + 선택 설비 DB 설정→target 변환 + orchestrator createRun.
 * {@link #isEnabled()} 토글(전이용 안전망)로 controller 가 enabled=false 면 503 폴백(프론트 job 경로). 9칸 e2e PASS
 * 후 M7 cutover 에서 토글 제거 + always-on.
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

    private static final String MODE_ALL = "ALL";
    private static final String MODE_EQUIPMENT_ONLY = "EQUIPMENT_ONLY";
    private static final String MODE_UPSTREAM_ONLY = "UPSTREAM_ONLY";

    private final UiSimulatorProperties properties;
    private final SimulatorOrchestratorClient client;
    private final SimulatorModeMapper modeMapper;
    private final SimulatorTargetAssembler targetAssembler;
    private final DemoRunConnectionGate connectionGate;
    private final UiDualRequestProperties dualRequestProperties;
    private final DemoRunConnectionRegistry runRegistry;

    public LiveMapSimulatorRunFacade(
            final UiSimulatorProperties properties,
            final SimulatorOrchestratorClient client,
            final SimulatorModeMapper modeMapper,
            final SimulatorTargetAssembler targetAssembler,
            final DemoRunConnectionGate connectionGate,
            final UiDualRequestProperties dualRequestProperties,
            final DemoRunConnectionRegistry runRegistry
    ) {
        this.properties = Objects.requireNonNull(properties, "properties is null");
        this.client = Objects.requireNonNull(client, "client is null");
        this.modeMapper = Objects.requireNonNull(modeMapper, "modeMapper is null");
        this.targetAssembler = Objects.requireNonNull(targetAssembler, "targetAssembler is null");
        this.connectionGate = Objects.requireNonNull(connectionGate, "connectionGate is null");
        this.dualRequestProperties = Objects.requireNonNull(dualRequestProperties, "dualRequestProperties is null");
        this.runRegistry = Objects.requireNonNull(runRegistry, "runRegistry is null");
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 모달 시작 — testType→mode + 선택 설비별 DB 설정→target. 연결-후-MES 배리어: orchestrator createRun 으로 설비를
     * bind 한 뒤(ALL 은 MES 보류) 설비 연결을 먼저 확인하고, 그 다음에만 테스트 로직(MES)을 활성화한다(모드별 분기는
     * 클래스 javadoc 참조). 연결/활성화 실패 시 run 을 중단·해제하고 예외를 전파한다(조용한 부분 시작 금지).
     */
    public SimulatorRunResponse startRun(final String testType, final String scenarioId, final List<String> eqpIds) {
        final String mode = modeMapper.toMode(testType);
        final List<SimulatorCreateRunRequest.SimulatorTargetRequest> targets = eqpIds.stream()
                .map(targetAssembler::toTarget)
                .toList();
        final long timeoutMs = dualRequestProperties.getDualRequestTimeoutMs();

        // 1) orchestrator createRun — 설비 bind(ALL/EQUIPMENT_ONLY) 또는 MES inject(UPSTREAM_ONLY). ALL 은 MES 보류.
        final SimulatorRunResponse response = client.createRun(new SimulatorCreateRunRequest(mode, scenarioId, targets));
        try {
            // 2) 테스트 로직 전에 설비를 먼저 연결·확인한다(실사용자 절차 모사).
            switch (mode) {
                case MODE_ALL -> {
                    // ACTIVE: EQP_START → PASS(=실제 연결). 반환된 connected 집합이 종료 시 원복 대상이라 즉시 추적한다.
                    final Set<String> captured = connectionGate.ensureActiveTargetsConnected(eqpIds, timeoutMs);
                    runRegistry.track(response.runId(), captured);
                    connectionGate.awaitPassiveTargetsConnected(eqpIds, timeoutMs); // PASSIVE: dial-in → connectionState=CONN
                    // 3) 연결 확인 완료 → 보류했던 MES 활성화(이제 송신해도 유실 없음).
                    client.activateUpstream(response.runId());
                }
                case MODE_EQUIPMENT_ONLY -> {
                    // 설비 시나리오는 handshake(연결) 시 시작·loop. ACTIVE 만 EQP_START 로 연결 유도(PASSIVE 는 dial-in 자동).
                    final Set<String> captured = connectionGate.ensureActiveTargetsConnected(eqpIds, timeoutMs);
                    runRegistry.track(response.runId(), captured);
                }
                case MODE_UPSTREAM_ONLY ->
                    // MES 는 createRun 에서 이미 주입·loop. ACTIVE 면 eqp_start 만 best-effort(실설비 대상, 연결 미보장).
                    // best-effort 라 connected 집합을 알 수 없어 추적하지 않는다(C6).
                    connectionGate.startActiveTargetsBestEffort(eqpIds, timeoutMs);
                default -> throw new UiBadRequestException("지원하지 않는 mode입니다: " + mode);
            }
        } catch (RuntimeException startFailure) {
            rollbackRun(response.runId(), timeoutMs, startFailure);
            throw startFailure;
        }
        return response;
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
        runRegistry.claim(runId).ifPresent(captured -> {
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

    public List<SimulatorScenarioCatalogItem> getScenarios(final String mode) {
        return client.getScenarios(mode);
    }
}
