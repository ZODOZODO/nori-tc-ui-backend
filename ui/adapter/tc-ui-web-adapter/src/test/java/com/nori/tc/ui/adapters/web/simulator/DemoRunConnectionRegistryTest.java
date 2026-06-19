package com.nori.tc.ui.adapters.web.simulator;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DemoRunConnectionRegistry} 의 추적/원자적 claim/스냅샷을 검증한다.
 *
 * <p>mock 의의: claim 이 단 한 번만 set 을 내주는 "원자적 remove-and-return"(R4)이 깨지면 stop·reaper 이중 END 가
 * 재현되도록, 연속 claim 2회 중 1회만 set 을 받는지 직접 검증한다(무력 mock 아님).
 */
class DemoRunConnectionRegistryTest {

    private final DemoRunConnectionRegistry registry = new DemoRunConnectionRegistry();

    @Test
    void trackThenClaimReturnsSameSet() {
        registry.track("RUN-1", Set.of("eqpA", "eqpB"));

        final Optional<Set<String>> claimed = registry.claim("RUN-1");

        assertTrue(claimed.isPresent());
        assertEquals(Set.of("eqpA", "eqpB"), claimed.get());
    }

    @Test
    void claimIsAtomicSoSecondClaimGetsEmpty() {
        registry.track("RUN-1", Set.of("eqpA"));

        final Optional<Set<String>> first = registry.claim("RUN-1");
        final Optional<Set<String>> second = registry.claim("RUN-1");

        assertTrue(first.isPresent());
        assertFalse(second.isPresent(), "원자적 claim — 두 번째 claim 은 빈 결과여야 한다(이중 END 방지)");
    }

    @Test
    void claimOnUnknownRunIsEmpty() {
        assertFalse(registry.claim("UNKNOWN").isPresent());
    }

    @Test
    void trackRecordsEmptySetAsTrackedKey() {
        registry.track("RUN-EMPTY", Set.of());

        assertEquals(Set.of("RUN-EMPTY"), registry.trackedRunIds());
        assertEquals(Optional.of(Set.of()), registry.claim("RUN-EMPTY"));
    }

    @Test
    void trackedRunIdsIsSnapshotIndependentOfMutation() {
        registry.track("RUN-1", Set.of("eqpA"));
        final Set<String> snapshot = registry.trackedRunIds();

        registry.track("RUN-2", Set.of("eqpB"));

        assertEquals(Set.of("RUN-1"), snapshot, "스냅샷은 이후 변이에 영향받지 않아야 한다");
        assertEquals(Set.of("RUN-1", "RUN-2"), registry.trackedRunIds());
    }
}
