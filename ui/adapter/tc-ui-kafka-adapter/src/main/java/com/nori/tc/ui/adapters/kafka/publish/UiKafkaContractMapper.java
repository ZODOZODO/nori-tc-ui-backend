package com.nori.tc.ui.adapters.kafka.publish;

import com.nori.tc.messaging.kafka.contract.GatewayEquipmentProfileSnapshot;
import com.nori.tc.ui.core.model.UiEquipmentProfileSnapshot;

/**
 * UI core 의미 모델을 Kafka 계약 모델로 변환하는 어댑터 전용 매퍼입니다.
 */
final class UiKafkaContractMapper {

    private UiKafkaContractMapper() {
    }

    static GatewayEquipmentProfileSnapshot toGatewayEquipmentProfileSnapshot(
            final UiEquipmentProfileSnapshot snapshot
    ) {
        if (snapshot == null) {
            return null;
        }

        return new GatewayEquipmentProfileSnapshot(
                snapshot.eqpKey(),
                snapshot.eqpId(),
                snapshot.commInterfaceType(),
                snapshot.socketType(),
                snapshot.hsmsDeviceId(),
                snapshot.eqpIp(),
                snapshot.eqpPort(),
                snapshot.modelKey(),
                snapshot.gatewayJarKey(),
                snapshot.businessJarKey(),
                snapshot.connectionMode(),
                snapshot.routePartition(),
                snapshot.enabled(),
                toHsmsSettings(snapshot.hsmsSettings()),
                toSocketSettings(snapshot.socketSettings()),
                toCurrentState(snapshot.currentStateSnapshot()),
                snapshot.portStatuses().stream()
                        .map(UiKafkaContractMapper::toPortStatus)
                        .toList(),
                toLogPolicy(snapshot.logPolicy()),
                snapshot.params().stream()
                        .map(UiKafkaContractMapper::toParam)
                        .toList(),
                snapshot.updatedAt(),
                snapshot.desiredState(),
                toPlcSettings(snapshot.plcSettings())
        );
    }

    private static GatewayEquipmentProfileSnapshot.PlcSettingsSnapshot toPlcSettings(
            final UiEquipmentProfileSnapshot.PlcSettingsSnapshot snapshot
    ) {
        if (snapshot == null) {
            return null;
        }
        // groups(디코드 스펙)를 계약으로 운반한다 — gateway 가 런타임 DB 재조회 없이 payload 를 정본으로 사용.
        return new GatewayEquipmentProfileSnapshot.PlcSettingsSnapshot(
                snapshot.plcProtocol(),
                snapshot.transport(),
                snapshot.unitId(),
                snapshot.pollTimeoutMs(),
                snapshot.reconnectMaxRetries(),
                snapshot.enabledPollGroups(),
                snapshot.groups().stream()
                        .map(UiKafkaContractMapper::toPlcGroup)
                        .toList()
        );
    }

    private static GatewayEquipmentProfileSnapshot.PlcGroupSnapshot toPlcGroup(
            final UiEquipmentProfileSnapshot.PlcGroupSnapshot group
    ) {
        return new GatewayEquipmentProfileSnapshot.PlcGroupSnapshot(
                group.messageName(),
                group.area(),
                group.startAddress(),
                group.readCount(),
                group.addressQualifier(),
                group.pollPeriodMs(),
                group.description(),
                group.registers().stream()
                        .map(UiKafkaContractMapper::toPlcRegister)
                        .toList()
        );
    }

    private static GatewayEquipmentProfileSnapshot.PlcRegisterSnapshot toPlcRegister(
            final UiEquipmentProfileSnapshot.PlcRegisterSnapshot register
    ) {
        return new GatewayEquipmentProfileSnapshot.PlcRegisterSnapshot(
                register.wordOffset(),
                register.variableName(),
                register.dataType(),
                register.length(),
                register.bitIndex(),
                register.byteIndex(),
                register.byteOrder(),
                register.wordOrder(),
                register.scale(),
                register.bcdSigned(),
                register.charEncoding(),
                register.description()
        );
    }

    private static GatewayEquipmentProfileSnapshot.HsmsSettingsSnapshot toHsmsSettings(
            final UiEquipmentProfileSnapshot.HsmsSettingsSnapshot snapshot
    ) {
        if (snapshot == null) {
            return null;
        }
        return new GatewayEquipmentProfileSnapshot.HsmsSettingsSnapshot(
                snapshot.deviceId(),
                snapshot.connectionMode(),
                snapshot.t3Timeout(),
                snapshot.t5Timeout(),
                snapshot.t6Timeout(),
                snapshot.t7Timeout(),
                snapshot.t8Timeout(),
                snapshot.linkTestEnabled(),
                snapshot.linkTestInterval(),
                snapshot.maxMsgBytes()
        );
    }

    private static GatewayEquipmentProfileSnapshot.SocketSettingsSnapshot toSocketSettings(
            final UiEquipmentProfileSnapshot.SocketSettingsSnapshot snapshot
    ) {
        if (snapshot == null) {
            return null;
        }
        return new GatewayEquipmentProfileSnapshot.SocketSettingsSnapshot(
                snapshot.socketProtocolType(),
                snapshot.connectionMode(),
                snapshot.charset(),
                snapshot.heartbeatEnabled(),
                snapshot.heartbeatInterval(),
                snapshot.readTimeout(),
                snapshot.writeTimeout(),
                snapshot.maxFrameSizeBytes(),
                snapshot.keepAliveEnabled()
        );
    }

    private static GatewayEquipmentProfileSnapshot.CurrentStateSnapshot toCurrentState(
            final UiEquipmentProfileSnapshot.CurrentStateSnapshot snapshot
    ) {
        if (snapshot == null) {
            return null;
        }
        return new GatewayEquipmentProfileSnapshot.CurrentStateSnapshot(
                snapshot.controlState(),
                snapshot.eqpState(),
                snapshot.sinceAt(),
                snapshot.reasonCode(),
                snapshot.reasonDetail(),
                snapshot.updatedAt()
        );
    }

    private static GatewayEquipmentProfileSnapshot.PortStatusSnapshot toPortStatus(
            final UiEquipmentProfileSnapshot.PortStatusSnapshot snapshot
    ) {
        return new GatewayEquipmentProfileSnapshot.PortStatusSnapshot(
                snapshot.portId(),
                snapshot.portType(),
                snapshot.portState(),
                snapshot.carrierId(),
                snapshot.carrierType(),
                snapshot.carrierState(),
                snapshot.updatedAt()
        );
    }

    private static GatewayEquipmentProfileSnapshot.LogPolicySnapshot toLogPolicy(
            final UiEquipmentProfileSnapshot.LogPolicySnapshot snapshot
    ) {
        if (snapshot == null) {
            return null;
        }
        return new GatewayEquipmentProfileSnapshot.LogPolicySnapshot(
                snapshot.logLevel(),
                snapshot.logRetentionDays(),
                snapshot.logPath(),
                snapshot.updatedAt()
        );
    }

    private static GatewayEquipmentProfileSnapshot.ParamSnapshot toParam(
            final UiEquipmentProfileSnapshot.ParamSnapshot snapshot
    ) {
        return new GatewayEquipmentProfileSnapshot.ParamSnapshot(
                snapshot.eqpParamKey(),
                snapshot.paramName(),
                snapshot.paramVersion(),
                snapshot.paramValue(),
                snapshot.updatedAt()
        );
    }
}
