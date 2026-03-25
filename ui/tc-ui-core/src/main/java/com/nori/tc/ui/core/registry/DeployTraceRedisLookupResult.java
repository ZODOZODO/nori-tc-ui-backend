package com.nori.tc.ui.core.registry;

import com.nori.tc.ui.core.deploy.DeployTraceCompletion;

/**
 * Deploy trace 분산 저장소 조회 결과입니다.
 *
 * @param status 조회 상태
 * @param completion 완료 결과
 * @param errorMessage 인프라 오류 메시지
 */
public record DeployTraceRedisLookupResult(
        Status status,
        DeployTraceCompletion completion,
        String errorMessage
) {

    public enum Status {
        PENDING,
        COMPLETED,
        INFRA_ERROR
    }

    public static DeployTraceRedisLookupResult pending() {
        return new DeployTraceRedisLookupResult(Status.PENDING, null, null);
    }

    public static DeployTraceRedisLookupResult completed(final DeployTraceCompletion completion) {
        return new DeployTraceRedisLookupResult(Status.COMPLETED, completion, null);
    }

    public static DeployTraceRedisLookupResult infraError(final String errorMessage) {
        return new DeployTraceRedisLookupResult(Status.INFRA_ERROR, null, errorMessage);
    }
}
