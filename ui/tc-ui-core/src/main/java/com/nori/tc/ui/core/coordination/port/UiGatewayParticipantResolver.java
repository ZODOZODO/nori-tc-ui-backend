package com.nori.tc.ui.core.coordination.port;

import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;

import java.util.List;

/**
 * operation 의 Gateway 참여자(= 그 설비 route partition 의 현재 소유 session)를 특정합니다(§11.3/§12.2).
 *
 * <p>Gateway 는 UI 명령 토픽을 <b>고정 partition assign</b> 으로 구독하므로, 한 설비의 명령은
 * 그 partition 을 소유한 Pod <b>하나</b>에만 도달합니다. 그래서 참여자 집합에 소유자가 아닌 Gateway
 * Pod 을 넣으면 그 Pod 은 받지도 못한 명령의 증적을 요구받고 barrier 는 영구히 닫히지 않습니다.</p>
 *
 * <p>route partition 이동만 예외입니다 — §12.5 의 순서는 "옛 owner 가 PREPARE 에서 release 하고 PREPARED
 * proof 를 남긴다 → 전원의 prepare proof 로 COMMITTED 에 도달한다 → 새 owner 가 APPLY 에서 bind 한다" 이므로,
 * <b>옛 owner 와 새 owner 가 모두 참여자</b>여야 합니다. 새 owner 만 고정하면 아무도 release 하지 못하는
 * 이동을 기다리게 됩니다.</p>
 */
@FunctionalInterface
public interface UiGatewayParticipantResolver {

    /**
     * manifest 가 요구하는 Gateway 참여자 session 을 모두 반환합니다.
     *
     * <p>일반 변경은 목표 partition 의 소유자 1 개이고, route partition 이동은 옛 소유자와 새 소유자
     * 2 개입니다(같은 shard group 이 양쪽을 소유하면 1 개로 합쳐집니다).</p>
     *
     * <p>하나라도 특정할 수 없으면 <b>빈 목록</b>을 반환합니다 — 일부만 고정하면 증적을 낼 수 없는
     * 참여자를 기다리거나 release 없는 bind 를 허용하게 되므로 fail-closed 가 옳습니다.</p>
     *
     * @param manifest 원장에 고정된 manifest
     * @return 참여자 session id 목록(특정 불가 시 빈 목록)
     */
    List<String> ownerSessionIds(UiOperationManifestView manifest);

    /**
     * Gateway 참여자를 특정하지 않는 resolver 입니다 — Gateway 참여가 필요 없는 구성/테스트용입니다.
     *
     * @return 항상 빈 목록을 반환하는 resolver
     */
    static UiGatewayParticipantResolver none() {
        return manifest -> List.of();
    }
}
