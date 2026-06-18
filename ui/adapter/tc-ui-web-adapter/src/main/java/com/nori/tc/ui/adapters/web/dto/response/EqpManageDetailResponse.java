package com.nori.tc.ui.adapters.web.dto.response;

import com.nori.tc.ui.domain.common.eqp.UiCarrierState;
import com.nori.tc.ui.domain.common.eqp.UiCarrierType;
import com.nori.tc.ui.domain.common.eqp.UiControlState;
import com.nori.tc.ui.domain.common.eqp.UiEqpState;
import com.nori.tc.ui.domain.common.eqp.UiLogLevel;
import com.nori.tc.ui.domain.common.eqp.UiPortType;
import com.nori.tc.ui.domain.common.model.UiModelStatus;
import com.nori.tc.ui.domain.common.model.UiProtocolType;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * EQP 관리 상세 응답 DTO입니다.
 */
public record EqpManageDetailResponse(
        String eqpId,
        long lineKey,
        UiProtocolType commInterface,
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
        PlcSettingsResponse plcSettings,
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
            UiControlState controlState,
            UiEqpState eqpState,
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
            UiLogLevel logLevel,
            Integer logRetentionDays,
            String logPath
    ) {
    }

    /**
     * EQP jar 연결 응답 DTO입니다.
     *
     * @param gatewayJarRef gateway jar 참조 키
     * @param gatewayJarFileName gateway jar 파일명
     * @param businessJarRef business jar 참조 키
     * @param businessJarFileName business jar 파일명
     */
    public record JarBindingResponse(
            Long gatewayJarRef,
            String gatewayJarFileName,
            Long businessJarRef,
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
            UiProtocolType commInterface,
            UiModelStatus status
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
     * PLC 전용 설정 응답 DTO입니다(UiEqpPlc 미러 — Model Info Update 왕복 시 plcSettings 보존용).
     */
    public record PlcSettingsResponse(
            String plcProtocol,
            String transport,
            Integer unitId,
            Integer pollTimeoutMs,
            Integer reconnectMaxRetries
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
            UiPortType portType,
            String portState,
            String carrierId,
            UiCarrierType carrierType,
            UiCarrierState carrierState,
            OffsetDateTime updatedAt
    ) {
    }
}
