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
        String updatedAt,
        String desiredState,
        PlcSettingsSnapshot plcSettings
) {

    public UiEquipmentProfileSnapshot {
        portStatuses = portStatuses == null ? List.of() : List.copyOf(portStatuses);
        params = params == null ? List.of() : List.copyOf(params);
    }

    /**
     * PLC 연결 튜닝 + 폴링 그룹 의도 스냅샷입니다(PLC 설비 전용 — SECS/SOCKET 은 null).
     *
     * <p>그룹/레지스터 디코드 스펙(groups)은 gateway 가 부팅/hot-reload 시 {@code tc_model_plc_*} 를
     * DB 직독(A안 §2/§3)하므로 ui-backend 는 운반하지 않는다. 연결 튜닝과 폴링 의도(enabledPollGroups)만 운반한다.</p>
     */
    public record PlcSettingsSnapshot(
            String plcProtocol,
            String transport,
            Integer unitId,
            Integer pollTimeoutMs,
            Integer reconnectMaxRetries,
            List<String> enabledPollGroups
    ) {
        public PlcSettingsSnapshot {
            enabledPollGroups = enabledPollGroups == null ? List.of() : List.copyOf(enabledPollGroups);
        }
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
