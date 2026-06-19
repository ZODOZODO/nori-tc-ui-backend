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
     * R4: 원자적 claim — {@code remove} 의 직전 값을 단 한 호출자에게만 반환한다. 동시 호출 시 한쪽만 set 을 받고
     * 나머지는 empty 라, set 을 받은 쪽만 EQP_END 를 발행하도록 보장한다(이중 END 방지).
     */
    public Optional<Set<String>> claim(final String runId) {
        return Optional.ofNullable(capturedByRunId.remove(runId));
    }

    /** reaper 순회용 키 스냅샷 — 순회 중 동시 변이로 인한 CME 를 피하려고 복사본을 반환한다. */
    public Set<String> trackedRunIds() {
        return Set.copyOf(capturedByRunId.keySet());
    }
}
