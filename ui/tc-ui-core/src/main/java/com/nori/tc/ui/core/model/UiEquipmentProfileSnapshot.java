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
     * PLC 연결 튜닝 + 폴링 그룹 의도 + 그룹/레지스터 디코드 스펙 스냅샷입니다(PLC 설비 전용 — SECS/SOCKET 은 null).
     *
     * <p>그룹/레지스터 디코드 스펙(groups)을 deploy 이벤트 payload 로 운반해 gateway 가 런타임 DB 재조회 없이
     * payload 를 정본으로 사용하게 한다(business 모델 캐시 패턴과 통일). enabledPollGroups 는 폴 활성 의도이며
     * 부팅/재연결 복원은 gateway 가 DB 에서 유지한다(이 SPEC 대상 아님).</p>
     */
    public record PlcSettingsSnapshot(
            String plcProtocol,
            String transport,
            Integer unitId,
            Integer pollTimeoutMs,
            Integer reconnectMaxRetries,
            List<String> enabledPollGroups,
            List<PlcGroupSnapshot> groups
    ) {
        public PlcSettingsSnapshot {
            enabledPollGroups = enabledPollGroups == null ? List.of() : List.copyOf(enabledPollGroups);
            groups = groups == null ? List.of() : List.copyOf(groups);
        }
    }

    /**
     * PLC 폴링 그룹(읽기 명령 1건) 디코드 스냅샷입니다(계약 {@code GatewayEquipmentProfileSnapshot.PlcGroupSnapshot} 미러).
     */
    public record PlcGroupSnapshot(
            String messageName,
            String area,
            Integer startAddress,
            Integer readCount,
            String addressQualifier,
            Integer pollPeriodMs,
            String description,
            List<PlcRegisterSnapshot> registers
    ) {
        public PlcGroupSnapshot {
            registers = registers == null ? List.of() : List.copyOf(registers);
        }
    }

    /**
     * PLC 레지스터(응답 워드 → 변수 디코드 규칙) 스냅샷입니다(계약 {@code PlcRegisterSnapshot} 미러).
     */
    public record PlcRegisterSnapshot(
            Integer wordOffset,
            String variableName,
            String dataType,
            Integer length,
            Integer bitIndex,
            Integer byteIndex,
            String byteOrder,
            String wordOrder,
            Double scale,
            Boolean bcdSigned,
            String charEncoding,
            String description
    ) {
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
