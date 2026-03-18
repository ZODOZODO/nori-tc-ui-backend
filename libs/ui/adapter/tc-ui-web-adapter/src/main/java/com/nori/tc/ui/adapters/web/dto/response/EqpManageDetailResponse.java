package com.nori.tc.ui.adapters.web.dto.response;

import com.nori.tc.db.domain.common.eqp.CarrierState;
import com.nori.tc.db.domain.common.eqp.CarrierType;
import com.nori.tc.db.domain.common.eqp.ControlState;
import com.nori.tc.db.domain.common.eqp.EqpState;
import com.nori.tc.db.domain.common.eqp.LogLevel;
import com.nori.tc.db.domain.common.eqp.PortState;
import com.nori.tc.db.domain.common.eqp.PortType;
import com.nori.tc.db.domain.common.model.ModelStatus;
import com.nori.tc.db.domain.common.model.ProtocolType;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * EQP 관리 상세 응답 DTO입니다.
 */
public record EqpManageDetailResponse(
        String eqpId,
        ProtocolType commInterface,
        String commMode,
        boolean isDev,
        Integer routePartition,
        String eqpIp,
        int eqpPort,
        boolean enabled,
        RuntimeStateResponse runtimeState,
        LogPolicyResponse logPolicy,
        JarBindingResponse jars,
        ModelBindingResponse modelBinding,
        HsmsSettingsResponse hsmsSettings,
        SocketSettingsResponse socketSettings,
        String appliedParamVersion,
        String appliedParamDescription,
        List<ParamVersionOptionResponse> paramVersions,
        List<PortStatusResponse> portStatuses
) {

    /**
     * EQP 런타임 상태 응답 DTO입니다.
     *
     * @param controlState control state
     * @param eqpState eqp state
     * @param connectionState connection state
     */
    public record RuntimeStateResponse(
            ControlState controlState,
            EqpState eqpState,
            String connectionState
    ) {
    }

    /**
     * EQP 로그 정책 응답 DTO입니다.
     *
     * @param logLevel 로그 레벨
     * @param logRetentionDays 로그 보관 일수
     * @param logPath 로그 경로
     */
    public record LogPolicyResponse(
            LogLevel logLevel,
            Integer logRetentionDays,
            String logPath
    ) {
    }

    /**
     * EQP jar 연결 응답 DTO입니다.
     *
     * @param gatewayJarFileName gateway jar 파일명
     * @param businessJarFileName business jar 파일명
     */
    public record JarBindingResponse(
            String gatewayJarFileName,
            String businessJarFileName
    ) {
    }

    /**
     * EQP model 연결 응답 DTO입니다.
     *
     * @param modelVersionKey model version key
     * @param modelKey model key
     * @param modelName model name
     * @param parentModel parent model
     * @param modelVersion model version
     * @param commInterface 통신 인터페이스
     * @param status 모델 상태
     */
    public record ModelBindingResponse(
            Long modelVersionKey,
            Long modelKey,
            String modelName,
            String parentModel,
            String modelVersion,
            ProtocolType commInterface,
            ModelStatus status
    ) {
    }

    /**
     * SECS 전용 설정 응답 DTO입니다.
     */
    public record HsmsSettingsResponse(
            Integer deviceId,
            Integer t3Timeout,
            Integer t5Timeout,
            Integer t6Timeout,
            Integer t7Timeout,
            Integer t8Timeout,
            Boolean linkTestEnabled,
            Integer linkTestInterval,
            Long maxMsgBytes
    ) {
    }

    /**
     * SOCKET 전용 설정 응답 DTO입니다.
     */
    public record SocketSettingsResponse(
            String socketProtocolType,
            String charset,
            Boolean heartbeatEnabled,
            Integer heartbeatInterval,
            Integer readTimeout,
            Integer writeTimeout,
            Integer maxFrameSizeBytes,
            Boolean keepAliveEnabled
    ) {
    }

    /**
     * EQP 파라미터 버전 옵션 응답 DTO입니다.
     */
    public record ParamVersionOptionResponse(
            String paramVersion,
            String description
    ) {
    }

    /**
     * EQP port status 응답 DTO입니다.
     */
    public record PortStatusResponse(
            String portId,
            PortType portType,
            PortState portState,
            String carrierId,
            CarrierType carrierType,
            CarrierState carrierState,
            OffsetDateTime updatedAt
    ) {
    }
}
