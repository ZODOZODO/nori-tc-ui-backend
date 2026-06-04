package com.nori.tc.ui.adapter.db.mapper;

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
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpHsms;
import com.nori.tc.ui.domain.eqp.UiEqpLogPolicy;
import com.nori.tc.ui.domain.eqp.UiEqpParam;
import com.nori.tc.ui.domain.eqp.UiEqpParamVersion;
import com.nori.tc.ui.domain.eqp.UiEqpPortStatus;
import com.nori.tc.ui.domain.eqp.UiEqpRuntimeState;
import com.nori.tc.ui.domain.eqp.UiEqpSocket;
import com.nori.tc.ui.domain.jar.UiBusinessJar;
import com.nori.tc.ui.domain.jar.UiGatewayJar;

/**
 * DB 설비/JAR 레코드와 UI 내부 모델 간 ACL 매퍼입니다.
 */
public final class UiEqpDbMapper {

    private UiEqpDbMapper() {
    }

    public static UiEqp toDomain(final TcEqp source) {
        if (source == null) {
            return null;
        }
        return new UiEqp(
                source.eqpKey(),
                source.eqpId(),
                source.lineKey(),
                UiDomainEnumMapper.toUiProtocolType(source.commInterface()),
                source.commMode(),
                source.isDev(),
                source.routePartition(),
                source.eqpIp(),
                source.eqpPort(),
                source.modelVersionKey(),
                source.appliedParamVersion(),
                source.gatewayJarKey(),
                source.businessJarKey(),
                source.enabled(),
                source.createdAt(),
                source.updatedAt(),
                source.createdBy(),
                source.updatedBy()
        );
    }

    public static UiEqpHsms toDomain(final TcEqpHsms source) {
        if (source == null) {
            return null;
        }
        return new UiEqpHsms(
                source.eqpKey(),
                source.deviceId(),
                source.t3Timeout(),
                source.t5Timeout(),
                source.t6Timeout(),
                source.t7Timeout(),
                source.t8Timeout(),
                source.linkTestEnabled(),
                source.linkTestInterval(),
                source.maxMsgBytes(),
                source.createdAt(),
                source.updatedAt()
        );
    }

    public static UiEqpSocket toDomain(final TcEqpSocket source) {
        if (source == null) {
            return null;
        }
        return new UiEqpSocket(
                source.eqpKey(),
                source.socketProtocolType(),
                source.charset(),
                source.heartbeatEnabled(),
                source.heartbeatInterval(),
                source.readTimeout(),
                source.writeTimeout(),
                source.maxFrameSizeBytes(),
                source.keepAliveEnabled(),
                source.createdAt(),
                source.updatedAt()
        );
    }

    public static UiEqpLogPolicy toDomain(final TcEqpLog source) {
        if (source == null) {
            return null;
        }
        return new UiEqpLogPolicy(
                source.eqpKey(),
                UiDomainEnumMapper.toUiLogLevel(source.logLevel()),
                source.logRetentionDays(),
                source.logPath(),
                source.updatedAt()
        );
    }

    public static UiEqpRuntimeState toDomain(final TcEqpState source) {
        if (source == null) {
            return null;
        }
        return new UiEqpRuntimeState(
                source.eqpKey(),
                UiDomainEnumMapper.toUiControlState(source.controlState()),
                UiDomainEnumMapper.toUiEqpState(source.eqpState()),
                source.sinceAt(),
                source.reasonCode(),
                source.reasonDetail(),
                source.updatedAt()
        );
    }

    public static UiEqpPortStatus toDomain(final TcEqpPortStatus source) {
        if (source == null) {
            return null;
        }
        return new UiEqpPortStatus(
                source.eqpPortStatusKey(),
                source.eqpKey(),
                source.portId(),
                UiDomainEnumMapper.toUiPortType(source.portType()),
                source.portState(),
                source.carrierId(),
                UiDomainEnumMapper.toUiCarrierType(source.carrierType()),
                UiDomainEnumMapper.toUiCarrierState(source.carrierState()),
                source.updatedAt()
        );
    }

    public static UiEqpParam toDomain(final TcEqpParam source) {
        if (source == null) {
            return null;
        }
        return new UiEqpParam(
                source.eqpParamKey(),
                source.eqpKey(),
                source.paramName(),
                source.paramVersion(),
                source.paramValue(),
                source.description(),
                source.createdBy(),
                source.updatedAt()
        );
    }

    public static UiEqpParamVersion toDomain(final TcEqpParamVersion source) {
        if (source == null) {
            return null;
        }
        return new UiEqpParamVersion(
                source.eqpParamVersionKey(),
                source.eqpKey(),
                source.paramVersion(),
                source.versionDescription(),
                source.createdAt(),
                source.updatedAt(),
                source.createdBy(),
                source.updatedBy()
        );
    }

    public static UiGatewayJar toDomain(final TcJarGateway source) {
        if (source == null) {
            return null;
        }
        return new UiGatewayJar(
                source.gatewayJarKey(),
                source.jarFileName(),
                source.jarFile(),
                source.createdAt(),
                source.updatedAt(),
                source.createdBy(),
                source.updatedBy()
        );
    }

    public static UiBusinessJar toDomain(final TcJarBusiness source) {
        if (source == null) {
            return null;
        }
        return new UiBusinessJar(
                source.businessJarKey(),
                source.jarFileName(),
                source.jarFile(),
                source.createdAt(),
                source.updatedAt(),
                source.createdBy(),
                source.updatedBy()
        );
    }
}
