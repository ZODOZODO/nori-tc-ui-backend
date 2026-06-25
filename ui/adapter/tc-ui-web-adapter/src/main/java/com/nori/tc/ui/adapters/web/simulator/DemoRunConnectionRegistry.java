package com.nori.tc.ui.adapters.web.simulator;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 데모-run 이 시작 시 EQP_START 로 연결한 설비 집합을 {@code runId → Set<eqpId>} 로 추적하는 레지스트리.
 *
 * <p>"이 run 이 연결한 설비"(=원복 대상)는 종료 시점엔 사전 연결 설비와 똑같이 desired_state=STARTED 라 현재
 * 상태만으로 구분 불가하다. 따라서 시작 시점의 connected 집합을 기억해 둔다. facade(stop/rollback)와 reaper 가
 * 공유하며, 이중 EQP_END 를 막기 위해 추적 해제는 {@link #claim(String)} 의 원자적 remove-and-return 으로만 한다.</p>
 */
public final class DemoRunConnectionRegistry {

    private final ConcurrentMap<String, Set<String>> capturedByRunId = new ConcurrentHashMap<>();

    /** R1: 시작 시 연결한 설비 집합을 불변 스냅샷으로 기록한다. 빈 집합도 키는 남겨 추적 대상으로 둔다. */
    public void track(final String runId, final Set<String> capturedEqpIds) {
        capturedByRunId.put(runId, Set.copyOf(capturedEqpIds));
    }

    /**
     * 비동기 배리어 race 기준점 — 요청 스레드가 createRun 직후 빈 집합으로 추적을 연다. 이후 백그라운드 activation 이
     * 실제 captured 를 {@link #commitCaptured} 로 채운다. 이 사이 stop/stopTarget 이 들어와도 항상 tracked run 을
     * claim 하게 되어(빈 집합이라도) "추적된 run" 경로로 일관 처리된다.
     */
    public void markPending(final String runId) {
        track(runId, Set.of());
    }

    /**
     * 백그라운드 activation 이 ensureActive 로 확보한 captured 를 원자적으로 commit 한다. 키가 아직 존재하면(=stop 이
     * 먼저 claim 하지 않음) 교체하고 true. 이미 claim(stop)되어 키가 사라졌으면 false — 이 경우 호출자(백그라운드)가
     * 방금 EQP_START 한 captured 를 직접 release 해야 누수가 없다(R4 race-safety).
     */
    public boolean commitCaptured(final String runId, final Set<String> capturedEqpIds) {
        final Set<String> snapshot = Set.copyOf(capturedEqpIds);
        return capturedByRunId.computeIfPresent(runId, (key, previous) -> snapshot) != null;
    }

    /**
     * R4: 원자적 claim — {@code remove} 의 직전 값을 단 한 호출자에게만 반환한다. 동시 호출 시 한쪽만 set 을 받고
     * 나머지는 empty 라, set 을 받은 쪽만 EQP_END 를 발행하도록 보장한다(이중 END 방지).
     */
    public Optional<Set<String>> claim(final String runId) {
        return Optional.ofNullable(capturedByRunId.remove(runId));
    }

    /**
     * 설비-단위 종료(부분 해제) — run 의 captured-set 에서 한 설비만 원자적으로 제거한다. 제거되면 true. 이후
     * run-level stop/reaper 의 claim 이 이 설비를 다시 EQP_END 하지 않게 한다(이중 END 레이스 차단). set 이 비어도
     * 키는 남겨 run 추적을 유지한다(아직 다른 target 이 실행 중일 수 있음).
     */
    public boolean release(final String runId, final String eqpId) {
        final boolean[] removed = {false};
        capturedByRunId.computeIfPresent(runId, (key, captured) -> {
            if (!captured.contains(eqpId)) {
                return captured;
            }
            removed[0] = true;
            final Set<String> next = new java.util.LinkedHashSet<>(captured);
            next.remove(eqpId);
            return Set.copyOf(next);
        });
        return removed[0];
    }

    /** reaper 순회용 키 스냅샷 — 순회 중 동시 변이로 인한 CME 를 피하려고 복사본을 반환한다. */
    public Set<String> trackedRunIds() {
        return Set.copyOf(capturedByRunId.keySet());
    }
}
