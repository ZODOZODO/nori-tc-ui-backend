package com.nori.tc.ui.core.deploy;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * scenario / step 조건식입니다.
 *
 * <p>자유식 문자열 대신 delta 집합 비교만 허용해 사람이 읽기 쉬운 구조를 유지합니다.</p>
 */
public record DeployScenarioCondition(
        Set<DeployDeltaKind> deltaAll,
        Set<DeployDeltaKind> deltaAny,
        Set<DeployDeltaKind> deltaNone
) {

    public DeployScenarioCondition {
        deltaAll = immutableCopy(deltaAll);
        deltaAny = immutableCopy(deltaAny);
        deltaNone = immutableCopy(deltaNone);
    }

    public boolean matches(final Set<DeployDeltaKind> actualDeltaKinds) {
        final Set<DeployDeltaKind> actual = actualDeltaKinds == null
                ? Set.of()
                : Set.copyOf(actualDeltaKinds);

        if (!actual.containsAll(deltaAll)) {
            return false;
        }
        if (!deltaAny.isEmpty() && deltaAny.stream().noneMatch(actual::contains)) {
            return false;
        }
        return deltaNone.stream().noneMatch(actual::contains);
    }

    public boolean isEmpty() {
        return deltaAll.isEmpty() && deltaAny.isEmpty() && deltaNone.isEmpty();
    }

    public static DeployScenarioCondition none() {
        return new DeployScenarioCondition(Set.of(), Set.of(), Set.of());
    }

    private static Set<DeployDeltaKind> immutableCopy(final Set<DeployDeltaKind> value) {
        if (value == null || value.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(new LinkedHashSet<>(value.stream().filter(Objects::nonNull).toList()));
    }
}
