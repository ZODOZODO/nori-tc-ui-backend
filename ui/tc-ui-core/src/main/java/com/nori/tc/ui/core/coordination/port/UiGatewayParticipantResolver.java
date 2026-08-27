package com.nori.tc.ui.core.coordination.port;

import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;

import java.util.Optional;

/**
 * operation 의 Gateway 참여자(= 그 설비 route partition 의 현재 소유 session)를 특정합니다(§11.3/§12.2).
 *
 * <p>Gateway 는 UI 명령 토픽을 <b>고정 partition assign</b> 으로 구독하므로, 한 설비의 명령은
 * 그 partition 을 소유한 Pod <b>하나</b>에만 도달합니다. 그래서 참여자 집합에 소유자가 아닌 Gateway
 * Pod 을 넣으면 그 Pod 은 받지도 못한 명령의 증적을 요구받고 barrier 는 영구히 닫히지 않습니다.</p>
 */
@FunctionalInterface
public interface UiGatewayParticipantResolver {

    /**
     * manifest 가 라우팅할 partition 의 현재 소유 Gateway session 을 반환합니다.
     *
     * @param manifest 원장에 고정된 manifest
     * @return 소유 session id. partition 을 정할 수 없거나 소유자가 없으면 empty
     */
    Optional<String> ownerSessionId(UiOperationManifestView manifest);

    /**
     * Gateway 참여자를 특정하지 않는 resolver 입니다 — Gateway 참여가 필요 없는 구성/테스트용입니다.
     *
     * @return 항상 empty 를 반환하는 resolver
     */
    static UiGatewayParticipantResolver none() {
        return manifest -> Optional.empty();
    }
}
