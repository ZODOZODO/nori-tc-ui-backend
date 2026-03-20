package com.nori.tc.ui.core.deploy;

/**
 * step retry 정책입니다.
 */
public record DeployScenarioRetryPolicy(
        int maxAttempts,
        long backoffMs
) {

    public DeployScenarioRetryPolicy {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be greater than 0");
        }
        if (backoffMs < 0) {
            throw new IllegalArgumentException("backoffMs must not be negative");
        }
    }

    public static DeployScenarioRetryPolicy noRetry() {
        return new DeployScenarioRetryPolicy(1, 0L);
    }
}
