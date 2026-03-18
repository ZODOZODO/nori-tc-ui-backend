package com.nori.tc.ui.core.eqp;

import com.nori.tc.db.domain.eqp.TcEqp;
import com.nori.tc.db.domain.eqp.TcEqpHsms;
import com.nori.tc.db.domain.eqp.TcEqpLog;
import com.nori.tc.db.domain.eqp.TcEqpParam;
import com.nori.tc.db.domain.eqp.TcEqpParamVersion;
import com.nori.tc.db.domain.eqp.TcEqpPortStatus;
import com.nori.tc.db.domain.eqp.TcEqpSocket;
import com.nori.tc.db.domain.eqp.TcEqpState;
import com.nori.tc.db.domain.jar.TcJarBusiness;
import com.nori.tc.db.domain.jar.TcJarGateway;
import com.nori.tc.db.domain.model.TcModel;

import java.util.List;
import java.util.Objects;

/**
 * EQP 관리 단건 스냅샷입니다.
 *
 * <p>조회 응답과 보상 복구 모두 같은 스냅샷을 재사용합니다.</p>
 */
public record EqpManagementSnapshot(
        TcEqp eqp,
        TcModel model,
        TcEqpHsms hsms,
        TcEqpSocket socket,
        TcEqpLog logPolicy,
        TcEqpState runtimeState,
        String connectionState,
        List<TcEqpPortStatus> portStatuses,
        List<TcEqpParam> params,
        List<TcEqpParamVersion> paramVersionMetas,
        TcJarGateway gatewayJar,
        TcJarBusiness businessJar
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
