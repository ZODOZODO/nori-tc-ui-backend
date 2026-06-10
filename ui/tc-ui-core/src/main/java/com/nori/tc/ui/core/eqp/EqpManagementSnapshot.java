package com.nori.tc.ui.core.eqp;

import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpHsms;
import com.nori.tc.ui.domain.eqp.UiEqpLogPolicy;
import com.nori.tc.ui.domain.eqp.UiEqpParam;
import com.nori.tc.ui.domain.eqp.UiEqpParamVersion;
import com.nori.tc.ui.domain.eqp.UiEqpPlc;
import com.nori.tc.ui.domain.eqp.UiEqpPortStatus;
import com.nori.tc.ui.domain.eqp.UiEqpRuntimeState;
import com.nori.tc.ui.domain.eqp.UiEqpSocket;
import com.nori.tc.ui.domain.jar.UiBusinessJar;
import com.nori.tc.ui.domain.jar.UiGatewayJar;
import com.nori.tc.ui.domain.model.UiModel;

import java.util.List;
import java.util.Objects;

/**
 * EQP 관리 단건 스냅샷입니다.
 *
 * <p>조회 응답과 보상 복구 모두 같은 스냅샷을 재사용합니다.</p>
 */
public record EqpManagementSnapshot(
        UiEqp eqp,
        UiModel model,
        UiEqpHsms hsms,
        UiEqpSocket socket,
        UiEqpPlc plc,
        UiEqpLogPolicy logPolicy,
        UiEqpRuntimeState runtimeState,
        String connectionState,
        List<UiEqpPortStatus> portStatuses,
        List<UiEqpParam> params,
        List<UiEqpParamVersion> paramVersionMetas,
        UiGatewayJar gatewayJar,
        UiBusinessJar businessJar
) {

    /**
     * 리스트 필드를 불변 리스트로 정규화합니다.
     */
    public EqpManagementSnapshot {
        Objects.requireNonNull(eqp, "eqp is null");
        portStatuses = portStatuses == null ? List.of() : List.copyOf(portStatuses);
        params = params == null ? List.of() : List.copyOf(params);
        paramVersionMetas = paramVersionMetas == null ? List.of() : List.copyOf(paramVersionMetas);
    }
}
