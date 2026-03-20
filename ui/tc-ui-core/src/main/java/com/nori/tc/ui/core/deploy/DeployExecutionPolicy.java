package com.nori.tc.ui.core.deploy;

/**
 * Deploy 실행 정책입니다.
 */
public enum DeployExecutionPolicy {
    BEST_EFFORT,
    FAIL_FAST;

    public static DeployExecutionPolicy fromText(final String text) {
        if (text == null || text.isBlank()) {
            return BEST_EFFORT;
        }
        try {
            return DeployExecutionPolicy.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported executionPolicy: " + text, exception);
        }
    }
}
