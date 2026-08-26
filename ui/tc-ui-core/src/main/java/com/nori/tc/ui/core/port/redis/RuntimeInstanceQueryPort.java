package com.nori.tc.ui.core.port.redis;

import java.util.List;

/**
 * Gateway / Business 런타임 인스턴스(Pod) 조망 조회 포트입니다.
 *
 * <p>{@link GatewayShardLeasePort} 가 답하는 것은 "어느 shard group 이 점유되었나"이고, 이 포트가
 * 답하는 것은 "어느 Pod 이 살아 있나"입니다. 두 축은 개수가 다릅니다 — shard group 수는 설정값이고
 * Pod 수는 replica 수라서, lease 만 보면 슬롯을 못 잡은 대기(spare) Pod 이 화면에서 사라집니다.</p>
 *
 * <p>원천은 coordination membership 원장입니다. Pod 은 lease 획득 여부와 무관하게 기동 시 자기 session 을
 * 등록하고 heartbeat 로 TTL 을 갱신하므로, spare Pod 도 여기에는 남습니다.</p>
 *
 * <p>두 축을 나눈 이유는 조인 키가 다르기 때문입니다 — gateway 는 shard lease 의 소유 {@code sessionId}
 * 로, business 는 Kafka consumer group 멤버의 host(= Pod IP)로 맞춥니다.</p>
 */
public interface RuntimeInstanceQueryPort {

    /**
     * 살아 있는 Gateway 인스턴스 목록을 조회합니다.
     *
     * @return sessionId 오름차순 인스턴스 목록. 조정 원장을 쓰지 않는 구성이면 빈 목록.
     */
    List<RuntimeInstanceView> findGatewayInstances();

    /**
     * 살아 있는 Business 인스턴스 목록을 조회합니다.
     *
     * @return sessionId 오름차순 인스턴스 목록. 조정 원장을 쓰지 않는 구성이면 빈 목록.
     */
    List<RuntimeInstanceView> findBusinessInstances();

    /**
     * 살아 있는 인스턴스 한 대입니다.
     *
     * @param sessionId {@code serviceName:podUid:bootId}. gateway shard lease 소유자 판정의 조인 키입니다.
     * @param podName Pod 이름. 알 수 없으면 null
     * @param podIp Pod IP. Kafka consumer group 멤버의 host 와 맞추는 조인 키입니다. 알 수 없으면 null
     * @param state membership 상태(JOINING / ACTIVE_SYNCED / STOPPING / FENCED 등)
     * @param protocolVersion 이 session 의 coordination protocol version
     * @param registeredAtEpochMs session 등록 시각(ms). 알 수 없으면 null
     * @param updatedAtEpochMs 마지막 heartbeat 시각(ms). 알 수 없으면 null
     */
    record RuntimeInstanceView(
            String sessionId,
            String podName,
            String podIp,
            String state,
            String protocolVersion,
            Long registeredAtEpochMs,
            Long updatedAtEpochMs
    ) {
    }
}
