package com.nori.tc.ui.adapters.web.dto.response;

/**
 * 런타임 인스턴스(Pod) 조망 응답 DTO입니다.
 *
 * @param sessionId {@code serviceName:podUid:bootId}. gateway 는 shard lease 의
 *                  {@code ownerInstanceId} 와 같은 문자열이라 점유 판정의 조인 키입니다.
 * @param podName Pod 이름. 알 수 없으면 null
 * @param podIp Pod IP. business 는 Kafka consumer group 멤버의 host 와 이 값으로 맞춥니다. 없으면 null
 * @param state membership 상태(JOINING / ACTIVE_SYNCED / STOPPING / FENCED 등)
 * @param protocolVersion 이 session 의 coordination protocol version
 * @param registeredAtEpochMs session 등록 시각(ms). 알 수 없으면 null
 * @param updatedAtEpochMs 마지막 heartbeat 시각(ms). 알 수 없으면 null
 */
public record RuntimeInstanceResponse(
        String sessionId,
        String podName,
        String podIp,
        String state,
        String protocolVersion,
        Long registeredAtEpochMs,
        Long updatedAtEpochMs
) {
}
