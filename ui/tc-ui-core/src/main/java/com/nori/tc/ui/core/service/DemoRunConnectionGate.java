package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.eqp.EqpAsyncAcceptResult;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.eqp.StartOutcome;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.domain.common.eqp.UiControlState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 데모-run 시작/종료 시 ACTIVE 대상 설비의 gateway 연결 라이프사이클을 조율하는 게이트입니다.
 *
 * <p>배경: ACTIVE 설비는 {@code desired_state=STARTED} 일 때만 gateway 가 dial-out 한다. 데모-run 은 실사용자가
 * UI 에서 연결한 뒤 시나리오를 돌리는 절차를 모사해야 하므로, run 시작 전 미연결(control_state 기준) ACTIVE 대상에
 * EQP_START(desired=ENDED) 또는 EQP_RECONNECT(desired=STARTED, stuck)를 발행해 연결을 확인한 뒤에만 진행한다.
 * PASSIVE 는 dial-in 이라 listener 가 상시 개방돼 있어(enabled 로 gate — gateway R-E1) per-run 동작이 필요 없으므로
 * skip 한다.</p>
 *
 * <p>desired_state 의 STARTED/ENDED 전이는 EQP_START/END 시나리오(단일 writer)가 수행하며, 본 게이트는 그 명령을
 * 발행하고 결과를 관측할 뿐 desired_state 를 직접 쓰지 않는다.</p>
 */
@Service
public class DemoRunConnectionGate {

    private static final Logger log = LoggerFactory.getLogger(DemoRunConnectionGate.class);
    private static final String PASSIVE = "PASSIVE";
    private static final String STARTED = "STARTED";
    private static final long CONNECTION_POLL_INTERVAL_MS = 150L;

    private final EqpManagementService eqpManagementService;

    public DemoRunConnectionGate(final EqpManagementService eqpManagementService) {
        this.eqpManagementService = Objects.requireNonNull(eqpManagementService, "eqpManagementService is null");
    }

    /**
     * 데모-run 대상 중 미연결 ACTIVE 설비에 EQP_START/EQP_RECONNECT 를 발행하고 gateway 연결(PASS)을 확인합니다.
     *
     * <p>PASSIVE 대상과 이미 연결된(control_state=REMOTE/LOCAL) ACTIVE 대상은 skip 합니다. 끊김 대상은 desired 로
     * 라우팅합니다 — ENDED 는 EQP_START(run 소유), STARTED(stuck)는 EQP_RECONNECT(run 소유 아님, R-B1). 대상 전부에
     * 먼저 발행한 뒤 공유 deadline 으로 동시에 await 하여 최악 1 timeout window 안에 끝냅니다. 하나라도 발행
     * 실패/FAIL/TIMEOUT 이면 이번 호출이 EQP_START 한 설비 전부에 보상 EQP_END 를 발행한 뒤 예외를 던집니다(조용한
     * 부분 시작 금지).</p>
     *
     * @param eqpIds run 대상 설비 ID 목록
     * @param timeoutMs EQP_START 응답 대기 최대 시간(ms)
     * @return 이번 호출이 EQP_START 로 연결시킨 설비 ID 집합(입력 순서 보존)
     * @throws UiConflictException 대상 중 하나라도 연결에 실패한 경우(부분 시작은 롤백됨)
     */
    public Set<String> ensureActiveTargetsConnected(final List<String> eqpIds, final long timeoutMs) {
        final LinkedHashSet<String> distinctEqpIds = distinctNonBlank(eqpIds);
        if (distinctEqpIds.isEmpty()) {
            return Set.of();
        }

        // 1) ACTIVE 대상을 실연결(control_state)로 분류 (PASSIVE 는 skip):
        //    - 연결됨(REMOTE/LOCAL) → skip
        //    - 끊김 + desired=STARTED(stuck) → EQP_RECONNECT (run 소유 아님 — 이미 STARTED)
        //    - 끊김 + desired=ENDED → EQP_START (run 이 연결시킴 = run 소유)
        final Map<String, String> startTargets = new LinkedHashMap<>();
        final Map<String, String> reconnectTargets = new LinkedHashMap<>();
        for (final String eqpId : distinctEqpIds) {
            final EqpManagementSnapshot snapshot = requireSnapshot(eqpId);
            if (isPassive(snapshot) || isConnected(snapshot)) {
                continue;
            }
            final String interfaceType = snapshot.eqp().commInterface().name();
            if (isStarted(snapshot)) {
                reconnectTargets.put(eqpId, interfaceType);
            } else {
                startTargets.put(eqpId, interfaceType);
            }
        }
        if (startTargets.isEmpty() && reconnectTargets.isEmpty()) {
            return Set.of();
        }

        // 2) 발행 (traceId 수집). run 소유(보상 END 대상)는 EQP_START 발행분에 한정한다.
        final Map<String, String> issuedStartInterfaceByEqpId = new LinkedHashMap<>();
        final Map<String, String> traceIdByEqpId = new LinkedHashMap<>();
        for (final Map.Entry<String, String> target : startTargets.entrySet()) {
            final String eqpId = target.getKey();
            final String interfaceType = target.getValue();
            final EqpAsyncAcceptResult accept = eqpManagementService.startAsync(eqpId, interfaceType, null, timeoutMs);
            if (!accept.accepted()) {
                releaseIssued(issuedStartInterfaceByEqpId, timeoutMs);
                throw new UiConflictException(
                        "데모-run 대상 설비 연결(EQP_START) 발행에 실패했습니다. eqpId=" + eqpId
                                + ", errorCode=" + accept.errorCode() + ", errorMessage=" + accept.errorMessage());
            }
            issuedStartInterfaceByEqpId.put(eqpId, interfaceType);
            traceIdByEqpId.put(eqpId, accept.traceId());
        }
        for (final Map.Entry<String, String> target : reconnectTargets.entrySet()) {
            final String eqpId = target.getKey();
            final String interfaceType = target.getValue();
            final EqpAsyncAcceptResult accept = eqpManagementService.reconnectAsync(eqpId, interfaceType, null, timeoutMs);
            if (!accept.accepted()) {
                releaseIssued(issuedStartInterfaceByEqpId, timeoutMs);
                throw new UiConflictException(
                        "데모-run 대상 설비 재연결(EQP_RECONNECT) 발행에 실패했습니다. eqpId=" + eqpId
                                + ", errorCode=" + accept.errorCode() + ", errorMessage=" + accept.errorMessage());
            }
            traceIdByEqpId.put(eqpId, accept.traceId());
        }

        // 3) 공유 deadline 으로 전부 await (이미 동시 진행 중 → 합산이 아닌 1 window)
        final long deadlineMs = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        final List<String> failures = new ArrayList<>();
        for (final Map.Entry<String, String> traced : traceIdByEqpId.entrySet()) {
            final long remainingMs = Math.max(0L, deadlineMs - System.currentTimeMillis());
            final StartOutcome outcome = eqpManagementService.awaitAsyncResult(traced.getValue(), remainingMs);
            if (!outcome.isPass()) {
                failures.add(traced.getKey() + "(" + outcome.state()
                        + (outcome.errorCode() == null ? "" : ":" + outcome.errorCode()) + ")");
            }
        }

        // 4) 하나라도 실패 → 이번 호출이 EQP_START 한 run 소유 설비만 보상 END 후 throw (조용한 부분 시작 금지).
        //    EQP_RECONNECT 한 stuck 대상은 run 소유가 아니므로 보상 END 대상이 아니다(이미 desired=STARTED).
        if (!failures.isEmpty()) {
            releaseIssued(issuedStartInterfaceByEqpId, timeoutMs);
            throw new UiConflictException(
                    "데모-run 대상 설비 연결에 실패했습니다. 부분 시작을 롤백했습니다. 실패=" + failures);
        }

        log.info("데모-run ACTIVE 대상 연결 완료. startedEqpIds={}, reconnectedStuckEqpIds={}",
                issuedStartInterfaceByEqpId.keySet(), reconnectTargets.keySet());
        // run 소유(EQP_START) 집합만 반환 → run 종료 시 이 집합만 EQP_END(stuck 재연결분 제외, D3).
        return new LinkedHashSet<>(issuedStartInterfaceByEqpId.keySet());
    }

    /**
     * 데모-run 대상 중 PASSIVE 설비가 실제 연결(control_state=REMOTE/LOCAL)될 때까지 대기합니다(ALL 모드 연결-후-MES 배리어).
     *
     * <p>ACTIVE 는 {@link #ensureActiveTargetsConnected} 의 EQP_START_REP PASS(=gateway lifecycle APPLIED=실제 dial 성공)로
     * 이미 연결이 확인되므로 여기서 다루지 않는다. PASSIVE 는 EQP_START 발행 대상이 아니라(상시 listener) 시뮬레이터가
     * createRun 에서 gateway 로 dial-in 하며, gateway 가 tc_eqp_state.control_state 를 REMOTE 로 기록한다. 그 현재 상태를
     * 폴링해 연결을 확인한 뒤에만 MES 활성화(activateUpstream)를 진행할 수 있다(미연결 MES 송신 = 유실).</p>
     *
     * @param eqpIds run 대상 설비 ID 목록
     * @param timeoutMs control_state=REMOTE/LOCAL 도달 최대 대기 시간(ms)
     * @throws UiConflictException 시간 내 연결 미도달 PASSIVE 대상이 있는 경우
     */
    public void awaitPassiveTargetsConnected(final List<String> eqpIds, final long timeoutMs) {
        final LinkedHashSet<String> distinctEqpIds = distinctNonBlank(eqpIds);
        final Set<String> pending = new LinkedHashSet<>();
        for (final String eqpId : distinctEqpIds) {
            if (isPassive(requireSnapshot(eqpId))) {
                pending.add(eqpId);
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        final long deadlineMs = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (true) {
            pending.removeIf(eqpId -> isConnected(eqpManagementService.getManageDetail(eqpId).orElse(null)));
            if (pending.isEmpty()) {
                log.info("데모-run PASSIVE 대상 연결(control_state=REMOTE/LOCAL) 확인 완료.");
                return;
            }
            if (System.currentTimeMillis() >= deadlineMs) {
                throw new UiConflictException(
                        "데모-run PASSIVE 대상 설비 연결 확인(control_state=REMOTE/LOCAL) 시간 초과. 미연결=" + pending);
            }
            sleepBeforeNextPoll();
        }
    }

    /**
     * UPSTREAM_ONLY(설비만 미시뮬레이트) run 시작 시 ACTIVE 대상에 EQP_START 를 best-effort 로 발행합니다.
     *
     * <p>UPSTREAM_ONLY 는 시뮬레이터가 설비를 bind 하지 않으므로(MES 만 활성) gateway dial 대상이 실설비뿐이라 데모
     * 환경에서 연결은 성립하지 않을 수 있다. 그래도 "active 면 eqp_start 후 로직 진행"(요구사항)을 충족하기 위해
     * desired_state=STARTED 만 설정하고, PASS 를 대기하거나 실패로 run 을 거부하지 않는다. PASSIVE/이미 STARTED 는 skip.</p>
     *
     * @param eqpIds run 대상 설비 ID 목록
     * @param timeoutMs EQP_START 발행(accept) 대기 시간(ms)
     */
    public void startActiveTargetsBestEffort(final List<String> eqpIds, final long timeoutMs) {
        final LinkedHashSet<String> distinctEqpIds = distinctNonBlank(eqpIds);
        for (final String eqpId : distinctEqpIds) {
            try {
                final EqpManagementSnapshot snapshot = requireSnapshot(eqpId);
                if (isPassive(snapshot) || isConnected(snapshot)) {
                    continue;
                }
                final String interfaceType = snapshot.eqp().commInterface().name();
                // 끊김+desired=STARTED(stuck)→EQP_RECONNECT / 끊김+desired=ENDED→EQP_START (R-B1).
                final EqpAsyncAcceptResult accept = isStarted(snapshot)
                        ? eqpManagementService.reconnectAsync(eqpId, interfaceType, null, timeoutMs)
                        : eqpManagementService.startAsync(eqpId, interfaceType, null, timeoutMs);
                if (!accept.accepted()) {
                    log.warn("UPSTREAM_ONLY 연결 발행 실패(무시하고 진행). eqpId={}, errorCode={}, errorMessage={}",
                            eqpId, accept.errorCode(), accept.errorMessage());
                }
            } catch (RuntimeException exception) {
                log.warn("UPSTREAM_ONLY 연결 처리 실패(무시하고 진행). eqpId={}", eqpId, exception);
            }
        }
    }

    /**
     * 데모-run 종료 시 호출자가 지정한 ACTIVE 설비들에 EQP_END 를 발행합니다(best-effort).
     *
     * <p>END 대상은 현재 상태(desired/control)로 재해석하지 않고 호출자가 넘긴 목록으로 한정한다(D3). 추적 run
     * (ALL/EQUIPMENT_ONLY)은 {@link #ensureActiveTargetsConnected} 가 반환한 captured set(run 이 EQP_START 로
     * 연결시킨 run 소유 집합)을 넘기므로, EQP_RECONNECT 로 복구만 한 stuck 대상(이미 desired=STARTED)은 종료되지
     * 않는다. PASSIVE 대상은 skip 하며, EQP_END 발행/응답이 실패해도 예외를 던지지 않고 로그만 남깁니다(run 종료
     * 자체는 진행되어야 함).</p>
     *
     * @param eqpIds run 대상 설비 ID 목록
     * @param timeoutMs EQP_END 응답 대기 최대 시간(ms)
     */
    public void releaseActiveTargets(final List<String> eqpIds, final long timeoutMs) {
        final LinkedHashSet<String> distinctEqpIds = distinctNonBlank(eqpIds);
        for (final String eqpId : distinctEqpIds) {
            try {
                final EqpManagementSnapshot snapshot = eqpManagementService.getManageDetail(eqpId).orElse(null);
                if (snapshot == null || isPassive(snapshot)) {
                    continue;
                }
                // 이 목록은 ensureActiveTargetsConnected 가 반환한 "이 run 이 EQP_START 로 연결시킨 run 소유 집합"이다.
                // EQP_RECONNECT 로 복구만 한 stuck 대상은 애초에 이 집합에 없어 종료되지 않는다(control_state 재해석 금지 — D3).
                issueEnd(eqpId, snapshot.eqp().commInterface().name(), timeoutMs);
            } catch (RuntimeException exception) {
                log.warn("데모-run 종료 시 EQP_END 처리 실패(무시하고 진행). eqpId={}", eqpId, exception);
            }
        }
    }

    private void releaseIssued(final Map<String, String> issuedInterfaceByEqpId, final long timeoutMs) {
        for (final Map.Entry<String, String> issued : issuedInterfaceByEqpId.entrySet()) {
            try {
                issueEnd(issued.getKey(), issued.getValue(), timeoutMs);
            } catch (RuntimeException exception) {
                log.warn("보상 EQP_END 처리 실패(무시). eqpId={}", issued.getKey(), exception);
            }
        }
    }

    private void issueEnd(final String eqpId, final String interfaceType, final long timeoutMs) {
        final EqpAsyncAcceptResult accept = eqpManagementService.endAsync(eqpId, interfaceType, null, timeoutMs);
        if (!accept.accepted()) {
            log.warn("EQP_END 발행 실패. eqpId={}, errorCode={}, errorMessage={}",
                    eqpId, accept.errorCode(), accept.errorMessage());
            return;
        }
        final StartOutcome outcome = eqpManagementService.awaitAsyncResult(accept.traceId(), timeoutMs);
        if (outcome.isPass()) {
            log.info("EQP_END 완료. eqpId={}", eqpId);
        } else {
            log.warn("EQP_END 미완료. eqpId={}, outcome={}, errorCode={}",
                    eqpId, outcome.state(), outcome.errorCode());
        }
    }

    private EqpManagementSnapshot requireSnapshot(final String eqpId) {
        return eqpManagementService.getManageDetail(eqpId)
                .orElseThrow(() -> new UiNotFoundException("설비를 찾을 수 없습니다. eqpId=" + eqpId));
    }

    private boolean isPassive(final EqpManagementSnapshot snapshot) {
        final String commMode = snapshot.eqp().commMode();
        return PASSIVE.equalsIgnoreCase(commMode == null ? "" : commMode.trim());
    }

    private boolean isStarted(final EqpManagementSnapshot snapshot) {
        // runtimeState 가 없으면(orElse(null)) 연결 의도 미확정 → not-STARTED 로 처리.
        if (snapshot.runtimeState() == null) {
            return false;
        }
        return STARTED.equalsIgnoreCase(snapshot.runtimeState().desiredState());
    }

    /**
     * 실제 연결 여부 — tc_eqp_state.control_state 가 REMOTE/LOCAL 이면 채널이 연결된 상태.
     *
     * <p>gateway 는 dial/bind 성공 시 control_state 를 REMOTE 로, 채널 종료 시 OFFLINE 으로 기록한다(현재 상태,
     * tc_eqp_state). connectionState(tc_eqp_state_hist 최신 CONN.to_state="STARTED"/"OFFLINE")는 이력 표시용이라
     * 배리어 판정에는 현재 상태인 control_state 를 직접 본다. OFFLINE/DOWN/DISCONNECTED 는 미연결.</p>
     */
    private boolean isConnected(final EqpManagementSnapshot snapshot) {
        if (snapshot == null || snapshot.runtimeState() == null) {
            return false;
        }
        final UiControlState controlState = snapshot.runtimeState().controlState();
        return controlState == UiControlState.REMOTE || controlState == UiControlState.LOCAL;
    }

    private void sleepBeforeNextPoll() {
        try {
            Thread.sleep(CONNECTION_POLL_INTERVAL_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new UiConflictException("데모-run 대상 설비 연결 확인 중 인터럽트되었습니다.");
        }
    }

    private LinkedHashSet<String> distinctNonBlank(final List<String> eqpIds) {
        final LinkedHashSet<String> result = new LinkedHashSet<>();
        if (eqpIds == null) {
            return result;
        }
        for (final String raw : eqpIds) {
            if (raw == null) {
                continue;
            }
            final String eqpId = raw.trim();
            if (!eqpId.isEmpty()) {
                result.add(eqpId);
            }
        }
        return result;
    }
}
