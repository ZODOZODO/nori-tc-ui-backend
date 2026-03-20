package com.nori.tc.ui.core.deploy;

/**
 * deploy target 집계 값입니다.
 */
public record DeployTargetCounts(
        int total,
        int pending,
        int noChange,
        int running,
        int completed,
        int failed,
        int rollingBack,
        int rolledBack,
        int compensationFailed,
        int canceled
) {

    public boolean hasActiveTarget() {
        return pending > 0 || running > 0 || rollingBack > 0;
    }

    public boolean hasFailure() {
        return failed > 0 || rolledBack > 0 || compensationFailed > 0;
    }

    public int successLikeCount() {
        return completed + noChange;
    }
}
