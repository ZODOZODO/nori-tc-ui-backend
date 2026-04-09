package com.nori.tc.ui.core.model;

import java.util.List;

/**
 * UI Core가 사용하는 설비 프로파일 스냅샷입니다.
 *
 * <p>Kafka 계약과 동일한 데이터 의미를 갖지만,
 * 코어 계층은 메시징 계약 모듈을 직접 참조하지 않도록
 * 자체 스냅샷 모델을 사용합니다.</p>
 */
public record UiEquipmentProfileSnapshot(
        Long eqpKey,
        String eqpId,
        String commInterfaceType,
        String socketType,
        Integer hsmsDeviceId,
        String eqpIp,
        Integer eqpPort,
        Long modelKey,
        Long gatewayJarKey,
        Long businessJarKey,
        String connectionMode,
        Integer routePartition,
        Boolean enabled,
        HsmsSettingsSnapshot hsmsSettings,
        SocketSettingsSnapshot socketSettings,
        CurrentStateSnapshot currentStateSnapshot,
        List<PortStatusSnapshot> portStatuses,
        LogPolicySnapshot logPolicy,
        List<ParamSnapshot> params,
        String updatedAt
) {

    public UiEquipmentProfileSnapshot {
        portStatuses = portStatuses == null ? List.of() : List.copyOf(portStatuses);
        params = params == null ? List.of() : List.copyOf(params);
    }

    public record HsmsSettingsSnapshot(
            Integer deviceId,
            String connectionMode,
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

    public record SocketSettingsSnapshot(
            String socketProtocolType,
            String connectionMode,
            String charset,
            Boolean heartbeatEnabled,
            Integer heartbeatInterval,
            Integer readTimeout,
            Integer writeTimeout,
            Integer maxFrameSizeBytes,
            Boolean keepAliveEnabled
    ) {
    }

    public record CurrentStateSnapshot(
            String controlState,
            String eqpState,
            String sinceAt,
            String reasonCode,
            String reasonDetail,
            String updatedAt
    ) {
    }

    public record PortStatusSnapshot(
            String portId,
            String portType,
            String portState,
            String carrierId,
            String carrierType,
            String carrierState,
            String updatedAt
    ) {
    }

    public record LogPolicySnapshot(
            String logLevel,
            Integer logRetentionDays,
            String logPath,
            String updatedAt
    ) {
    }

    public record ParamSnapshot(
            Long eqpParamKey,
            String paramName,
            String paramVersion,
            String paramValue,
            String updatedAt
    ) {
    }
}
