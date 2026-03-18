package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.eqp.store.TcEqpHsmsStore;
import com.nori.tc.db.core.eqp.store.TcEqpLogStore;
import com.nori.tc.db.core.eqp.store.TcEqpParamStore;
import com.nori.tc.db.core.eqp.store.TcEqpParamVersionStore;
import com.nori.tc.db.core.eqp.store.TcEqpPortStatusStore;
import com.nori.tc.db.core.eqp.store.TcEqpSocketStore;
import com.nori.tc.db.core.eqp.store.TcEqpStateStore;
import com.nori.tc.db.core.eqp.store.TcEqpStore;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqp;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqpHsms;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqpLog;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqpParam;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqpParamVersion;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqpPortStatus;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqpSocket;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqpState;
import com.nori.tc.db.core.jar.store.TcJarBusinessStore;
import com.nori.tc.db.core.jar.store.TcJarGatewayStore;
import com.nori.tc.db.core.jar.upsert.UpsertTcJarBusiness;
import com.nori.tc.db.core.jar.upsert.UpsertTcJarGateway;
import com.nori.tc.db.domain.common.eqp.ControlState;
import com.nori.tc.db.domain.common.eqp.EqpState;
import com.nori.tc.db.domain.common.eqp.LogLevel;
import com.nori.tc.db.domain.common.model.ProtocolType;
import com.nori.tc.db.domain.eqp.TcEqp;
import com.nori.tc.db.domain.eqp.TcEqpParam;
import com.nori.tc.db.domain.eqp.TcEqpParamVersion;
import com.nori.tc.db.domain.eqp.TcEqpPortStatus;
import com.nori.tc.db.domain.jar.TcJarBusiness;
import com.nori.tc.db.domain.jar.TcJarGateway;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * EQP 관리 저장/복구 포트의 DB store 기반 구현체입니다.
 */
@Repository
public class JpaEqpCrudPort implements EqpCrudPort {

    private final EqpManagementDbSupport dbSupport;
    private final TcEqpStore eqpStore;
    private final TcEqpHsmsStore eqpHsmsStore;
    private final TcEqpSocketStore eqpSocketStore;
    private final TcEqpLogStore eqpLogStore;
    private final TcEqpStateStore eqpStateStore;
    private final TcEqpPortStatusStore eqpPortStatusStore;
    private final TcEqpParamStore eqpParamStore;
    private final TcEqpParamVersionStore eqpParamVersionStore;
    private final TcJarGatewayStore jarGatewayStore;
    private final TcJarBusinessStore jarBusinessStore;

    public JpaEqpCrudPort(
            final EqpManagementDbSupport dbSupport,
            final TcEqpStore eqpStore,
            final TcEqpHsmsStore eqpHsmsStore,
            final TcEqpSocketStore eqpSocketStore,
            final TcEqpLogStore eqpLogStore,
            final TcEqpStateStore eqpStateStore,
            final TcEqpPortStatusStore eqpPortStatusStore,
            final TcEqpParamStore eqpParamStore,
            final TcEqpParamVersionStore eqpParamVersionStore,
            final TcJarGatewayStore jarGatewayStore,
            final TcJarBusinessStore jarBusinessStore
    ) {
        this.dbSupport = Objects.requireNonNull(dbSupport, "dbSupport is null");
        this.eqpStore = Objects.requireNonNull(eqpStore, "eqpStore is null");
        this.eqpHsmsStore = Objects.requireNonNull(eqpHsmsStore, "eqpHsmsStore is null");
        this.eqpSocketStore = Objects.requireNonNull(eqpSocketStore, "eqpSocketStore is null");
        this.eqpLogStore = Objects.requireNonNull(eqpLogStore, "eqpLogStore is null");
        this.eqpStateStore = Objects.requireNonNull(eqpStateStore, "eqpStateStore is null");
        this.eqpPortStatusStore = Objects.requireNonNull(eqpPortStatusStore, "eqpPortStatusStore is null");
        this.eqpParamStore = Objects.requireNonNull(eqpParamStore, "eqpParamStore is null");
        this.eqpParamVersionStore = Objects.requireNonNull(eqpParamVersionStore, "eqpParamVersionStore is null");
        this.jarGatewayStore = Objects.requireNonNull(jarGatewayStore, "jarGatewayStore is null");
        this.jarBusinessStore = Objects.requireNonNull(jarBusinessStore, "jarBusinessStore is null");
    }

    @Override
    public EqpManagementSnapshot create(final EqpManagementCommand.Create command) {
        validateCreateCommand(command);

        try {
            final TcEqp createdEqp = eqpStore.upsert(new UpsertTcEqp(
                    command.eqpId(),
                    command.interfaceType(),
                    normalizeCommMode(command.commMode()),
                    command.isDev(),
                    command.routePartition(),
                    command.eqpIp().trim(),
                    command.eqpPort(),
                    command.modelVersionKey(),
                    normalizeOptionalText(command.appliedParamVersion()),
                    true,
                    normalizeActor(command.actor()),
                    normalizeActor(command.actor())
            ));

            final long eqpKey = createdEqp.eqpKey();
            upsertProtocolDetails(eqpKey, command.interfaceType(), command.hsmsSettings(), command.socketSettings());
            upsertLogPolicy(eqpKey, resolveCreateLogSettings(command.logSettings()));
            upsertInitialState(eqpKey, command.interfaceType());
            upsertJars(eqpKey, command.gatewayJarFileName(), command.businessJarFileName(), command.actor(), true);

            return requireSnapshot(command.eqpId());
        } catch (RuntimeException exception) {
            throw toUiException("EQP 생성", exception);
        }
    }

    @Override
    public EqpManagementSnapshot update(final String eqpId, final EqpManagementCommand.Update command) {
        if (eqpId == null || eqpId.isBlank()) {
            throw new UiBadRequestException("eqpId는 비어 있을 수 없습니다.");
        }
        if (command == null) {
            throw new UiBadRequestException("요청 본문이 비어 있습니다.");
        }

        final EqpManagementSnapshot existing = requireSnapshot(eqpId);

        try {
            final TcEqp updatedEqp = eqpStore.upsert(new UpsertTcEqp(
                    existing.eqp().eqpId(),
                    existing.eqp().commInterface(),
                    normalizeCommMode(command.commMode()),
                    command.isDev(),
                    command.routePartition(),
                    command.eqpIp().trim(),
                    command.eqpPort(),
                    command.modelVersionKey(),
                    normalizeOptionalText(command.appliedParamVersion()),
                    existing.eqp().enabled(),
                    existing.eqp().createdBy(),
                    normalizeActor(command.actor())
            ));

            final long eqpKey = updatedEqp.eqpKey();
            upsertProtocolDetails(eqpKey, existing.eqp().commInterface(), command.hsmsSettings(), command.socketSettings());
            upsertLogPolicy(eqpKey, resolveUpdateLogSettings(command.logSettings(), existing.logPolicy()));
            upsertJars(eqpKey, command.gatewayJarFileName(), command.businessJarFileName(), command.actor(), false);

            return requireSnapshot(eqpId);
        } catch (RuntimeException exception) {
            throw toUiException("EQP 수정", exception);
        }
    }

    @Override
    public void restore(final EqpManagementSnapshot snapshot) {
        if (snapshot == null) {
            throw new UiBadRequestException("복구 스냅샷이 비어 있습니다.");
        }

        try {
            final TcEqp restoredEqp = eqpStore.upsert(new UpsertTcEqp(
                    snapshot.eqp().eqpId(),
                    snapshot.eqp().commInterface(),
                    snapshot.eqp().commMode(),
                    snapshot.eqp().isDev(),
                    snapshot.eqp().routePartition(),
                    snapshot.eqp().eqpIp(),
                    snapshot.eqp().eqpPort(),
                    snapshot.eqp().modelVersionKey(),
                    snapshot.eqp().appliedParamVersion(),
                    snapshot.eqp().enabled(),
                    snapshot.eqp().createdBy(),
                    snapshot.eqp().updatedBy()
            ));

            final long eqpKey = restoredEqp.eqpKey();
            restoreProtocolDetails(eqpKey, snapshot);
            restoreLogPolicy(eqpKey, snapshot);
            restoreState(eqpKey, snapshot);
            restoreJars(eqpKey, snapshot);
            restorePortStatuses(eqpKey, snapshot.portStatuses());
            restoreParams(eqpKey, snapshot.params());
            restoreParamVersionMetas(eqpKey, snapshot.paramVersionMetas());
        } catch (RuntimeException exception) {
            throw toUiException("EQP 복구", exception);
        }
    }

    @Override
    public void delete(final String eqpId) {
        if (eqpId == null || eqpId.isBlank()) {
            throw new UiBadRequestException("eqpId는 비어 있을 수 없습니다.");
        }

        try {
            eqpStore.deleteByEqpId(eqpId);
        } catch (RuntimeException exception) {
            throw toUiException("EQP 삭제", exception);
        }
    }

    @Override
    public Optional<EqpManagementSnapshot> findSnapshotByEqpId(final String eqpId) {
        if (eqpId == null || eqpId.isBlank()) {
            throw new UiBadRequestException("eqpId는 비어 있을 수 없습니다.");
        }

        try {
            return dbSupport.loadSnapshotByEqpId(eqpId);
        } catch (RuntimeException exception) {
            throw toUiException("EQP 조회", exception);
        }
    }

    private void validateCreateCommand(final EqpManagementCommand.Create command) {
        if (command == null) {
            throw new UiBadRequestException("요청 본문이 비어 있습니다.");
        }
        if (command.eqpId() == null || command.eqpId().isBlank()) {
            throw new UiBadRequestException("eqpId는 필수입니다.");
        }
        if (command.interfaceType() == null) {
            throw new UiBadRequestException("interfaceType은 필수입니다.");
        }
        if (command.hsmsSettings() == null && command.interfaceType() == ProtocolType.SECS) {
            throw new UiBadRequestException("SECS 설비는 hsmsSettings가 필요합니다.");
        }
        if (command.socketSettings() == null && command.interfaceType() == ProtocolType.SOCKET) {
            throw new UiBadRequestException("SOCKET 설비는 socketSettings가 필요합니다.");
        }
    }

    private EqpManagementSnapshot requireSnapshot(final String eqpId) {
        return dbSupport.loadSnapshotByEqpId(eqpId)
                .orElseThrow(() -> new UiBadRequestException("설비 저장 후 스냅샷을 찾을 수 없습니다."));
    }

    private void upsertProtocolDetails(
            final long eqpKey,
            final ProtocolType interfaceType,
            final EqpManagementCommand.HsmsSettings hsmsSettings,
            final EqpManagementCommand.SocketSettings socketSettings
    ) {
        if (interfaceType == ProtocolType.SECS) {
            final EqpManagementCommand.HsmsSettings resolved = resolveHsmsSettings(hsmsSettings);
            eqpHsmsStore.upsert(new UpsertTcEqpHsms(
                    eqpKey,
                    resolved.deviceId(),
                    resolved.t3Timeout(),
                    resolved.t5Timeout(),
                    resolved.t6Timeout(),
                    resolved.t7Timeout(),
                    resolved.t8Timeout(),
                    resolved.linkTestEnabled(),
                    resolved.linkTestInterval(),
                    resolved.maxMsgBytes(),
                    null,
                    null
            ));
            eqpSocketStore.deleteByEqpKey(eqpKey);
            return;
        }

        final EqpManagementCommand.SocketSettings resolved = resolveSocketSettings(socketSettings);
        eqpSocketStore.upsert(new UpsertTcEqpSocket(
                eqpKey,
                resolved.socketProtocolType(),
                resolved.charset(),
                resolved.heartbeatEnabled(),
                resolved.heartbeatInterval(),
                resolved.readTimeout(),
                resolved.writeTimeout(),
                resolved.maxFrameSizeBytes(),
                resolved.keepAliveEnabled(),
                null,
                null
        ));
        eqpHsmsStore.deleteByEqpKey(eqpKey);
    }

    private void upsertLogPolicy(final long eqpKey, final EqpManagementCommand.LogSettings logSettings) {
        eqpLogStore.upsert(new UpsertTcEqpLog(
                eqpKey,
                logSettings.logLevel(),
                logSettings.logRetentionDays(),
                logSettings.logPath()
        ));
    }

    private void upsertInitialState(final long eqpKey, final ProtocolType interfaceType) {
        eqpStateStore.upsert(createInitialStateCommand(eqpKey, interfaceType, OffsetDateTime.now()));
    }

    /**
     * EQP 생성 직후 저장할 초기 상태를 생성합니다.
     *
     * <p>설계 기준:</p>
     * <p>- SECS: DOWN / DOWN</p>
     * <p>- SOCKET: DISCONNECTED / SERVICE_UNAVAILABLE</p>
     *
     * <p>테스트에서 고정 시각을 주입해 검증할 수 있도록 명령 생성 로직을 분리합니다.</p>
     */
    static UpsertTcEqpState createInitialStateCommand(
            final long eqpKey,
            final ProtocolType interfaceType,
            final OffsetDateTime timestamp
    ) {
        if (eqpKey <= 0) {
            throw new IllegalArgumentException("eqpKey는 0보다 커야 합니다.");
        }
        if (interfaceType == null) {
            throw new IllegalArgumentException("interfaceType은 필수입니다.");
        }

        final OffsetDateTime resolvedTimestamp = Objects.requireNonNull(timestamp, "timestamp is null");
        final ControlState controlState;
        final EqpState eqpState;

        if (interfaceType == ProtocolType.SECS) {
            controlState = ControlState.DOWN;
            eqpState = EqpState.DOWN;
        } else {
            controlState = ControlState.DISCONNECTED;
            eqpState = EqpState.SERVICE_UNAVAILABLE;
        }

        return new UpsertTcEqpState(
                eqpKey,
                controlState,
                eqpState,
                resolvedTimestamp,
                "EQP_CREATED",
                "EQP created by UI management service",
                resolvedTimestamp
        );
    }

    private void upsertJars(
            final long eqpKey,
            final String gatewayJarFileName,
            final String businessJarFileName,
            final String actor,
            final boolean createMode
    ) {
        final String normalizedActor = normalizeActor(actor);
        final String normalizedGatewayJar = normalizeOptionalText(gatewayJarFileName);
        final String normalizedBusinessJar = normalizeOptionalText(businessJarFileName);

        if (normalizedGatewayJar != null) {
            final TcJarGateway source = dbSupport.findLatestGatewayJarByFileName(normalizedGatewayJar)
                    .orElseThrow(() -> new UiBadRequestException("선택한 Gateway Jar를 찾을 수 없습니다. filename=" + normalizedGatewayJar));
            jarGatewayStore.upsert(new UpsertTcJarGateway(
                    eqpKey,
                    source.jarFileName(),
                    source.jarFile(),
                    normalizedActor,
                    normalizedActor
            ));
        } else if (createMode) {
            jarGatewayStore.deleteByEqpKey(eqpKey);
        }

        if (normalizedBusinessJar != null) {
            final TcJarBusiness source = dbSupport.findLatestBusinessJarByFileName(normalizedBusinessJar)
                    .orElseThrow(() -> new UiBadRequestException("선택한 Business Jar를 찾을 수 없습니다. filename=" + normalizedBusinessJar));
            jarBusinessStore.upsert(new UpsertTcJarBusiness(
                    eqpKey,
                    source.jarFileName(),
                    source.jarFile(),
                    normalizedActor,
                    normalizedActor
            ));
        } else if (createMode) {
            jarBusinessStore.deleteByEqpKey(eqpKey);
        }
    }

    private void restoreProtocolDetails(final long eqpKey, final EqpManagementSnapshot snapshot) {
        if (snapshot.eqp().commInterface() == ProtocolType.SECS && snapshot.hsms() != null) {
            eqpHsmsStore.upsert(new UpsertTcEqpHsms(
                    eqpKey,
                    snapshot.hsms().deviceId(),
                    snapshot.hsms().t3Timeout(),
                    snapshot.hsms().t5Timeout(),
                    snapshot.hsms().t6Timeout(),
                    snapshot.hsms().t7Timeout(),
                    snapshot.hsms().t8Timeout(),
                    snapshot.hsms().linkTestEnabled(),
                    snapshot.hsms().linkTestInterval(),
                    snapshot.hsms().maxMsgBytes(),
                    snapshot.hsms().createdAt(),
                    snapshot.hsms().updatedAt()
            ));
            eqpSocketStore.deleteByEqpKey(eqpKey);
            return;
        }

        if (snapshot.eqp().commInterface() == ProtocolType.SOCKET && snapshot.socket() != null) {
            eqpSocketStore.upsert(new UpsertTcEqpSocket(
                    eqpKey,
                    snapshot.socket().socketProtocolType(),
                    snapshot.socket().charset(),
                    snapshot.socket().heartbeatEnabled(),
                    snapshot.socket().heartbeatInterval(),
                    snapshot.socket().readTimeout(),
                    snapshot.socket().writeTimeout(),
                    snapshot.socket().maxFrameSizeBytes(),
                    snapshot.socket().keepAliveEnabled(),
                    snapshot.socket().createdAt(),
                    snapshot.socket().updatedAt()
            ));
            eqpHsmsStore.deleteByEqpKey(eqpKey);
        }
    }

    private void restoreLogPolicy(final long eqpKey, final EqpManagementSnapshot snapshot) {
        if (snapshot.logPolicy() == null) {
            eqpLogStore.deleteByEqpKey(eqpKey);
            return;
        }

        eqpLogStore.upsert(new UpsertTcEqpLog(
                eqpKey,
                snapshot.logPolicy().logLevel(),
                snapshot.logPolicy().logRetentionDays(),
                snapshot.logPolicy().logPath()
        ));
    }

    private void restoreState(final long eqpKey, final EqpManagementSnapshot snapshot) {
        if (snapshot.runtimeState() == null) {
            eqpStateStore.deleteByEqpKey(eqpKey);
            return;
        }

        eqpStateStore.upsert(new UpsertTcEqpState(
                eqpKey,
                snapshot.runtimeState().controlState(),
                snapshot.runtimeState().eqpState(),
                snapshot.runtimeState().sinceAt(),
                snapshot.runtimeState().reasonCode(),
                snapshot.runtimeState().reasonDetail(),
                snapshot.runtimeState().updatedAt()
        ));
    }

    private void restoreJars(final long eqpKey, final EqpManagementSnapshot snapshot) {
        if (snapshot.gatewayJar() == null) {
            jarGatewayStore.deleteByEqpKey(eqpKey);
        } else {
            jarGatewayStore.upsert(new UpsertTcJarGateway(
                    eqpKey,
                    snapshot.gatewayJar().jarFileName(),
                    snapshot.gatewayJar().jarFile(),
                    snapshot.gatewayJar().createdBy(),
                    snapshot.gatewayJar().updatedBy()
            ));
        }

        if (snapshot.businessJar() == null) {
            jarBusinessStore.deleteByEqpKey(eqpKey);
        } else {
            jarBusinessStore.upsert(new UpsertTcJarBusiness(
                    eqpKey,
                    snapshot.businessJar().jarFileName(),
                    snapshot.businessJar().jarFile(),
                    snapshot.businessJar().createdBy(),
                    snapshot.businessJar().updatedBy()
            ));
        }
    }

    private void restorePortStatuses(final long eqpKey, final List<TcEqpPortStatus> snapshotPortStatuses) {
        final List<TcEqpPortStatus> existingPortStatuses = dbSupport.loadAllPortStatuses(eqpKey);
        for (TcEqpPortStatus existing : existingPortStatuses) {
            eqpPortStatusStore.deleteByEqpKeyPortId(eqpKey, existing.portId());
        }

        for (TcEqpPortStatus status : snapshotPortStatuses) {
            eqpPortStatusStore.upsert(new UpsertTcEqpPortStatus(
                    eqpKey,
                    status.portId(),
                    status.portType(),
                    status.portState(),
                    status.carrierId(),
                    status.carrierType(),
                    status.carrierState(),
                    status.updatedAt()
            ));
        }
    }

    private void restoreParams(final long eqpKey, final List<TcEqpParam> snapshotParams) {
        final List<TcEqpParam> existingParams = dbSupport.loadAllParams(eqpKey);
        for (TcEqpParam existing : existingParams) {
            eqpParamStore.deleteByEqpParamKey(existing.eqpParamKey());
        }

        for (TcEqpParam param : snapshotParams) {
            eqpParamStore.upsert(new UpsertTcEqpParam(
                    eqpKey,
                    param.paramName(),
                    param.paramVersion(),
                    param.paramValue(),
                    param.description(),
                    param.createdBy()
            ));
        }
    }

    private void restoreParamVersionMetas(final long eqpKey, final List<TcEqpParamVersion> snapshotParamVersionMetas) {
        eqpParamVersionStore.deleteAllByEqpKey(eqpKey);

        for (TcEqpParamVersion paramVersionMeta : snapshotParamVersionMetas) {
            eqpParamVersionStore.upsert(new UpsertTcEqpParamVersion(
                    eqpKey,
                    paramVersionMeta.paramVersion(),
                    paramVersionMeta.versionDescription(),
                    paramVersionMeta.createdBy(),
                    paramVersionMeta.updatedBy()
            ));
        }
    }

    private EqpManagementCommand.HsmsSettings resolveHsmsSettings(final EqpManagementCommand.HsmsSettings input) {
        if (input == null) {
            throw new UiBadRequestException("SECS 설비는 hsmsSettings가 필요합니다.");
        }
        return new EqpManagementCommand.HsmsSettings(
                input.deviceId() == null ? 0 : input.deviceId(),
                input.t3Timeout() == null ? 45 : input.t3Timeout(),
                input.t5Timeout() == null ? 10 : input.t5Timeout(),
                input.t6Timeout() == null ? 5 : input.t6Timeout(),
                input.t7Timeout() == null ? 10 : input.t7Timeout(),
                input.t8Timeout() == null ? 5 : input.t8Timeout(),
                input.linkTestEnabled() == null || input.linkTestEnabled(),
                input.linkTestInterval() == null ? 60 : input.linkTestInterval(),
                input.maxMsgBytes() == null ? 10_485_760L : input.maxMsgBytes()
        );
    }

    private EqpManagementCommand.SocketSettings resolveSocketSettings(final EqpManagementCommand.SocketSettings input) {
        if (input == null) {
            throw new UiBadRequestException("SOCKET 설비는 socketSettings가 필요합니다.");
        }
        if (input.socketProtocolType() == null || input.socketProtocolType().isBlank()) {
            throw new UiBadRequestException("socketProtocolType은 필수입니다.");
        }
        return new EqpManagementCommand.SocketSettings(
                input.socketProtocolType().trim(),
                normalizeOptionalText(input.charset()) == null ? "UTF-8" : input.charset().trim(),
                input.heartbeatEnabled() == null || input.heartbeatEnabled(),
                input.heartbeatInterval() == null ? 30 : input.heartbeatInterval(),
                input.readTimeout() == null ? 0 : input.readTimeout(),
                input.writeTimeout() == null ? 0 : input.writeTimeout(),
                input.maxFrameSizeBytes() == null ? 8192 : input.maxFrameSizeBytes(),
                input.keepAliveEnabled() == null || input.keepAliveEnabled()
        );
    }

    private EqpManagementCommand.LogSettings resolveCreateLogSettings(final EqpManagementCommand.LogSettings input) {
        return new EqpManagementCommand.LogSettings(
                input == null || input.logLevel() == null ? LogLevel.INFO : input.logLevel(),
                input == null || input.logRetentionDays() == null ? 7 : input.logRetentionDays(),
                resolveLogPath(input == null ? null : input.logPath(), "\\")
        );
    }

    private EqpManagementCommand.LogSettings resolveUpdateLogSettings(
            final EqpManagementCommand.LogSettings input,
            final com.nori.tc.db.domain.eqp.TcEqpLog existingLogPolicy
    ) {
        final LogLevel fallbackLogLevel = existingLogPolicy == null || existingLogPolicy.logLevel() == null
                ? LogLevel.INFO
                : existingLogPolicy.logLevel();
        final int fallbackRetentionDays = existingLogPolicy == null ? 7 : existingLogPolicy.logRetentionDays();
        final String fallbackLogPath = resolveLogPath(existingLogPolicy == null ? null : existingLogPolicy.logPath(), "\\");

        if (input == null) {
            return new EqpManagementCommand.LogSettings(
                    fallbackLogLevel,
                    fallbackRetentionDays,
                    fallbackLogPath
            );
        }

        return new EqpManagementCommand.LogSettings(
                input.logLevel() == null ? fallbackLogLevel : input.logLevel(),
                input.logRetentionDays() == null ? fallbackRetentionDays : input.logRetentionDays(),
                resolveLogPath(input.logPath(), fallbackLogPath)
        );
    }

    private String resolveLogPath(final String value, final String fallback) {
        final String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            return fallback;
        }
        return normalized;
    }

    private String normalizeOptionalText(final String value) {
        if (value == null) {
            return null;
        }
        final String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeActor(final String actor) {
        final String normalized = normalizeOptionalText(actor);
        return normalized == null ? "SYSTEM" : normalized;
    }

    private String normalizeCommMode(final String commMode) {
        final String normalized = normalizeOptionalText(commMode);
        if (normalized == null) {
            throw new UiBadRequestException("commMode는 필수입니다.");
        }
        return normalized.toUpperCase();
    }

    private RuntimeException toUiException(final String action, final RuntimeException exception) {
        if (exception instanceof UiBadRequestException || exception instanceof UiConflictException) {
            return exception;
        }
        if (UiDbAdapterExceptionSupport.isBadRequest(exception)) {
            return new UiBadRequestException(action + " 입력이 올바르지 않습니다.", exception);
        }
        if (UiDbAdapterExceptionSupport.isConflict(exception)) {
            return new UiConflictException(action + " 중 충돌이 발생했습니다.", exception);
        }
        return exception;
    }
}
