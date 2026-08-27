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

        /** 등록이 살아 있고 아직 기대 source 의 reply 가 다 모이지 않았습니다. */
        PENDING,

        /**
         * 등록 자체가 없습니다 — TTL 만료 또는 미등록.
         *
         * <p>{@code PENDING} 과 합치면 안 됩니다. 등록이 없으면 이후 도착하는 reply 는 기록조차
         * 되지 않으므로(기대 source 를 모른다) 이 trace 는 <b>영원히</b> 완료되지 않습니다.
         * 둘을 같은 값으로 접으면 그 영구 대기가 "정상 대기" 로 보입니다.</p>
         */
        EXPIRED,

        COMPLETED,
        INFRA_ERROR
    }

    public static DeployTraceRedisLookupResult pending() {
        return new DeployTraceRedisLookupResult(Status.PENDING, null, null);
    }

    public static DeployTraceRedisLookupResult expired() {
        return new DeployTraceRedisLookupResult(Status.EXPIRED, null, null);
    }

    public static DeployTraceRedisLookupResult completed(final DeployTraceCompletion completion) {
        return new DeployTraceRedisLookupResult(Status.COMPLETED, completion, null);
    }

    public static DeployTraceRedisLookupResult infraError(final String errorMessage) {
        return new DeployTraceRedisLookupResult(Status.INFRA_ERROR, null, errorMessage);
    }
}
