package com.nori.tc.ui.core.coordination.port;

import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
import com.nori.tc.ui.core.model.UiEquipmentProfileSnapshot;

/**
 * 조정 operation 의 Gateway 명령에 실을 설비 profile 을 manifest 로부터 만듭니다(§12.3/§12.4).
 *
 * <p>Gateway 는 명령 payload 의 {@code equipmentProfile} 이 없으면 context upsert 를
 * {@code EQP_PROFILE_PAYLOAD_REQUIRED} 로 거부하고, 컨텍스트를 DB 에서 lazy load 하지도 않습니다
 * ({@code GatewayUiRuntimeControlSupport.requireProfilePayload} / {@code resolveOrLoadContext}).
 * 그래서 조정 경로의 PREPARE/APPLY 는 <b>목표 상태 profile</b> 을 직접 운반해야 합니다.</p>
 *
 * <p>목표 상태의 출처는 원장에 고정된 manifest 입니다 — 로컬 요청 객체가 아니라 manifest 여야 다른 Pod
 * 가 인계받아도 같은 payload 가 재현됩니다(§13.4).</p>
 */
@FunctionalInterface
public interface UiGatewayCommandProfileFactory {

    /**
     * manifest 의 목표 상태를 Gateway 명령 payload 로 만듭니다.
     *
     * @param manifest 원장에 고정된 manifest
     * @return Gateway 명령에 실을 profile(이 operation 이 context upsert 를 요구하지 않으면 null)
     */
    UiEquipmentProfileSnapshot profileFor(UiOperationManifestView manifest);

    /**
     * profile 을 만들지 않는 구현입니다 — 조정이 꺼진 구성의 기본값입니다.
     *
     * @return 항상 null 을 반환하는 팩토리
     */
    static UiGatewayCommandProfileFactory none() {
        return manifest -> null;
    }
}
