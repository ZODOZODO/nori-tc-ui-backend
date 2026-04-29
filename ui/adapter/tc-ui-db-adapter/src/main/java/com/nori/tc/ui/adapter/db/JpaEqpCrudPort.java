package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.eqp.store.TcEqpHsmsStore;
import com.nori.tc.db.core.eqp.store.TcEqpLogStore;
import com.nori.tc.db.core.eqp.store.TcEqpParamStore;
import com.nori.tc.db.core.eqp.store.TcEqpParamVersionStore;
import com.nori.tc.db.core.eqp.store.TcEqpPortStatusStore;
import com.nori.tc.db.core.eqp.store.TcEqpSocketStore;
import com.nori.tc.db.core.eqp.store.TcEqpStateStore;
import com.nori.tc.db.core.eqp.store.TcEqpStore;
import com.nori.tc.db.core.model.store.TcModelParamStore;
import com.nori.tc.db.core.common.PageRequest;
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
import com.nori.tc.db.core.work.store.TcWorkStore;
import com.nori.tc.db.domain.common.eqp.ControlState;
import com.nori.tc.db.domain.common.eqp.EqpState;
import com.nori.tc.db.domain.eqp.TcEqp;
import com.nori.tc.db.domain.eqp.TcEqpParam;
import com.nori.tc.db.domain.model.TcModelParam;
import com.nori.tc.ui.adapter.db.mapper.UiDomainEnumMapper;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.port.db.DemoJobCleanupPort;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import com.nori.tc.ui.domain.common.eqp.UiLogLevel;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.eqp.UiEqpLogPolicy;
import com.nori.tc.ui.domain.eqp.UiEqpParam;
import com.nori.tc.ui.domain.eqp.UiEqpParamVersion;
import com.nori.tc.ui.domain.eqp.UiEqpPortStatus;
import org.springframework.transaction.annotation.Transactional;
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

    private static final String PORT_COUNT_PARAM_NAME = "PORTCOUNT";
    private static final String INITIAL_PORT_STATE = "down";
    private static final int DEFAULT_PORT_COUNT = 4;
    private static final int MODEL_PARAM_PAGE_LIMIT = 500;

    private final EqpManagementDbSupport dbSupport;
    private final TcEqpStore eqpStore;
    private final TcEqpHsmsStore eqpHsmsStore;
    private final TcEqpSocketStore eqpSocketStore;
    private final TcEqpLogStore eqpLogStore;
    private final TcEqpStateStore eqpStateStore;
    private final TcEqpPortStatusStore eqpPortStatusStore;
    private final TcEqpParamStore eqpParamStore;
    private final TcEqpParamVersionStore eqpParamVersionStore;
    private final TcModelParamStore modelParamStore;
    private final TcJarGatewayStore jarGatewayStore;
    private final TcJarBusinessStore jarBusinessStore;
    private final TcWorkStore workStore;
    private final DemoJobCleanupPort demoJobCleanupPort;

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
            final TcModelParamStore modelParamStore,
            final TcJarGatewayStore jarGatewayStore,
            final TcJarBusinessStore jarBusinessStore,
            final TcWorkStore workStore,
            final DemoJobCleanupPort demoJobCleanupPort
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
        this.modelParamStore = Objects.requireNonNull(modelParamStore, "modelParamStore is null");
        this.jarGatewayStore = Objects.requireNonNull(jarGatewayStore, "jarGatewayStore is null");
        this.jarBusinessStore = Objects.requireNonNull(jarBusinessStore, "jarBusinessStore is null");
        this.workStore = Objects.requireNonNull(workStore, "workStore is null");
        this.demoJobCleanupPort = Objects.requireNonNull(demoJobCleanupPort, "demoJobCleanupPort is null");
    }

    @Override
    @Transactional
    public EqpManagementSnapshot create(final EqpManagementCommand.Create command) {
        try {
            validateCreateCommand(command);
            validateSelectedJarRefs(command.gatewayJarRef(), command.businessJarRef());
            validatePassiveListenerGroupRoutePartition(
                    null,
                    command.interfaceType(),
                    command.commMode(),
                    command.routePartition(),
                    command.eqpIp(),
                    command.eqpPort(),
                    command.socketSettings()
            );

            final TcEqp createdEqp = eqpStore.upsert(new UpsertTcEqp(
                    command.eqpId(),
                    UiDomainEnumMapper.toDbProtocolType(command.interfaceType()),
                    normalizeCommMode(command.commMode()),
                    command.isDev(),
                    command.routePartition(),
                    command.eqpIp().trim(),
                    command.eqpPort(),
                    command.modelVersionKey(),
                    normalizeOptionalText(command.appliedParamVersion()),
                    normalizeOptionalJarRef(command.gatewayJarRef(), "gatewayJarRef"),
                    normalizeOptionalJarRef(command.businessJarRef(), "businessJarRef"),
                    true,
                    normalizeActor(command.actor()),
                    normalizeActor(command.actor())
            ));

            final long eqpKey = createdEqp.eqpKey();
            upsertProtocolDetails(eqpKey, command.interfaceType(), command.hsmsSettings(), command.socketSettings());
            upsertLogPolicy(eqpKey, resolveCreateLogSettings(command.logSettings()));
            upsertInitialState(eqpKey, command.interfaceType());
            upsertInitialPortStatuses(createdEqp, normalizeOptionalText(command.appliedParamVersion()));

            return requireSnapshot(command.eqpId());
        } catch (RuntimeException exception) {
            throw toUiException("EQP 생성", exception);
        }
    }

    @Override
    @Transactional
    public EqpManagementSnapshot update(final String eqpId, final EqpManagementCommand.Update command) {
        if (eqpId == null || eqpId.isBlank()) {
            throw new UiBadRequestException("eqpId는 비어 있을 수 없습니다.");
        }
        if (command == null) {
            throw new UiBadRequestException("요청 본문이 비어 있습니다.");
        }

        try {
            final EqpManagementSnapshot existing = requireSnapshot(eqpId);
            validateSelectedJarRefs(command.gatewayJarRef(), command.businessJarRef());
            validatePassiveListenerGroupRoutePartition(
                    existing.eqp().eqpId(),
                    existing.eqp().commInterface(),
                    command.commMode(),
                    command.routePartition(),
                    command.eqpIp(),
                    command.eqpPort(),
                    command.socketSettings()
            );

            final TcEqp updatedEqp = eqpStore.upsert(new UpsertTcEqp(
                    existing.eqp().eqpId(),
                    UiDomainEnumMapper.toDbProtocolType(existing.eqp().commInterface()),
                    normalizeCommMode(command.commMode()),
                    command.isDev(),
                    command.routePartition(),
                    command.eqpIp().trim(),
                    command.eqpPort(),
                    command.modelVersionKey(),
                    normalizeOptionalText(command.appliedParamVersion()),
                    normalizeOptionalJarRef(command.gatewayJarRef(), "gatewayJarRef"),
                    normalizeOptionalJarRef(command.businessJarRef(), "businessJarRef"),
                    existing.eqp().enabled(),
                    existing.eqp().createdBy(),
                    normalizeActor(command.actor())
            ));

            final long eqpKey = updatedEqp.eqpKey();
            upsertProtocolDetails(eqpKey, existing.eqp().commInterface(), command.hsmsSettings(), command.socketSettings());
            upsertLogPolicy(eqpKey, resolveUpdateLogSettings(command.logSettings(), existing.logPolicy()));

            return requireSnapshot(eqpId);
        } catch (RuntimeException exception) {
            throw toUiException("EQP 수정", exception);
        }
    }

    @Override
    @Transactional
    public void restore(final EqpManagementSnapshot snapshot) {
        if (snapshot == null) {
            throw new UiBadRequestException("복구 스냅샷이 비어 있습니다.");
        }
        validateSelectedJarRefs(snapshot.eqp().gatewayJarKey(), snapshot.eqp().businessJarKey());

        try {
            final TcEqp restoredEqp = eqpStore.upsert(new UpsertTcEqp(
                    snapshot.eqp().eqpId(),
                    UiDomainEnumMapper.toDbProtocolType(snapshot.eqp().commInterface()),
                    snapshot.eqp().commMode(),
                    snapshot.eqp().isDev(),
                    snapshot.eqp().routePartition(),
                    snapshot.eqp().eqpIp(),
                    snapshot.eqp().eqpPort(),
                    snapshot.eqp().modelVersionKey(),
                    snapshot.eqp().appliedParamVersion(),
                    snapshot.eqp().gatewayJarKey(),
                    snapshot.eqp().businessJarKey(),
                    snapshot.eqp().enabled(),
                    snapshot.eqp().createdBy(),
                    snapshot.eqp().updatedBy()
            ));

            final long eqpKey = restoredEqp.eqpKey();
            restoreProtocolDetails(eqpKey, snapshot);
            restoreLogPolicy(eqpKey, snapshot);
            restoreState(eqpKey, snapshot);
            restorePortStatuses(eqpKey, snapshot.portStatuses());
            restoreParams(eqpKey, snapshot.params());
            restoreParamVersionMetas(eqpKey, snapshot.paramVersionMetas());
        } catch (RuntimeException exception) {
            throw toUiException("EQP 복구", exception);
        }
    }

    @Override
    @Transactional
    public void delete(final String eqpId) {
        if (eqpId == null || eqpId.isBlank()) {
            throw new UiBadRequestException("eqpId는 비어 있을 수 없습니다.");
        }

        try {
            final EqpManagementSnapshot snapshot = requireSnapshot(eqpId);
            final long eqpKey = snapshot.eqp().eqpKey();

            // tc_demo_job은 nori-tc-demo-tester가 관리하는 별도 테이블이지만 tc_eqp(eqp_key) FK를 가집니다.
            // 활성 demo job이 남아 있으면 FK 위반으로 tc_eqp 삭제가 실패하므로 사전에 차단합니다.
            // (트랜잭션 내부에서 다시 한 번 검증해 사전 검증 이후 새로 추가된 활성 job 경합도 막습니다.)
            final List<String> activeDemoJobIds = demoJobCleanupPort.findActiveDemoJobIds(eqpKey);
            if (!activeDemoJobIds.isEmpty()) {
                throw new UiConflictException(
                        "해당 설비에서 실행 중인 demo job이 있어 삭제할 수 없습니다. demoJobIds=" + activeDemoJobIds
                );
            }
            // 종료 상태(COMPLETED/FAILED/CANCELED) demo job을 정리합니다.
            // (tc_demo_job_session, tc_demo_job_event는 ON DELETE CASCADE로 함께 정리됩니다.)
            demoJobCleanupPort.deleteAllByEqpKey(eqpKey);

            // tc_work는 ON DELETE NO ACTION이므로 명시적으로 정리합니다.
            workStore.deleteAllByEqpKey(eqpKey);
            // tc_eqp_* 부속 테이블(hsms/socket/state/state_hist/log/port_status/param/param_version/global)은
            // ON DELETE CASCADE로 자동 정리됩니다.
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
        if (command.hsmsSettings() == null && command.interfaceType() == UiProtocolType.SECS) {
            throw new UiBadRequestException("SECS 설비는 hsmsSettings가 필요합니다.");
        }
        if (command.socketSettings() == null && command.interfaceType() == UiProtocolType.SOCKET) {
            throw new UiBadRequestException("SOCKET 설비는 socketSettings가 필요합니다.");
        }
    }

    private void validatePassiveListenerGroupRoutePartition(
            final String currentEqpId,
            final UiProtocolType interfaceType,
            final String commMode,
            final Integer routePartition,
            final String eqpIp,
            final Integer eqpPort,
            final EqpManagementCommand.SocketSettings socketSettings
    ) {
        if (!"PASSIVE".equalsIgnoreCase(normalizeCommMode(commMode))) {
            return;
        }
        if (routePartition == null || eqpIp == null || eqpIp.isBlank() || eqpPort == null || eqpPort <= 0) {
            return;
        }

        final String socketProtocolType = interfaceType == UiProtocolType.SOCKET
                ? resolveSocketSettings(socketSettings).socketProtocolType()
                : null;

        dbSupport.findPassiveListenerGroupRoutePartitionConflict(
                        interfaceType,
                        currentEqpId,
                        eqpIp,
                        eqpPort,
                        routePartition,
                        socketProtocolType
                )
                .ifPresent(peerEqpId -> {
                    final String interfaceLabel = interfaceType == UiProtocolType.SOCKET ? "SOCKET" : "HSMS";
                    throw new UiBadRequestException(
                            "동일 PASSIVE " + interfaceLabel + " listener-group의 routePartition은 동일해야 합니다. "
                                    + "peerEqpId=" + peerEqpId
                    );
                });
    }

    private EqpManagementSnapshot requireSnapshot(final String eqpId) {
        return dbSupport.loadSnapshotByEqpId(eqpId)
                .orElseThrow(() -> new UiBadRequestException("설비 저장 후 스냅샷을 찾을 수 없습니다."));
    }

    private void upsertProtocolDetails(
            final long eqpKey,
            final UiProtocolType interfaceType,
            final EqpManagementCommand.HsmsSettings hsmsSettings,
            final EqpManagementCommand.SocketSettings socketSettings
    ) {
        if (interfaceType == UiProtocolType.SECS) {
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
                UiDomainEnumMapper.toDbLogLevel(logSettings.logLevel()),
                logSettings.logRetentionDays(),
                logSettings.logPath()
        ));
    }

    private void upsertInitialState(final long eqpKey, final UiProtocolType interfaceType) {
        eqpStateStore.upsert(createInitialStateCommand(eqpKey, interfaceType, OffsetDateTime.now()));
    }

    private void upsertInitialPortStatuses(final TcEqp eqp, final String appliedParamVersion) {
        final int portCount = resolveInitialPortCount(eqp, appliedParamVersion);
        final OffsetDateTime timestamp = OffsetDateTime.now();

        for (int portNumber = 1; portNumber <= portCount; portNumber++) {
            eqpPortStatusStore.upsert(new UpsertTcEqpPortStatus(
                    eqp.eqpKey(),
                    Integer.toString(portNumber),
                    null,
                    INITIAL_PORT_STATE,
                    null,
                    null,
                    null,
                    timestamp
            ));
        }
    }

    private int resolveInitialPortCount(final TcEqp eqp, final String appliedParamVersion) {
        return findEqpPortCount(eqp.eqpKey(), appliedParamVersion)
                .or(() -> findModelPortCount(eqp.modelVersionKey()))
                .orElse(DEFAULT_PORT_COUNT);
    }

    private Optional<Integer> findEqpPortCount(final long eqpKey, final String appliedParamVersion) {
        if (appliedParamVersion == null || appliedParamVersion.isBlank()) {
            return Optional.empty();
        }

        return eqpParamStore.findAllByEqpKeyAndVersion(eqpKey, appliedParamVersion).stream()
                .filter(Objects::nonNull)
                .filter(param -> PORT_COUNT_PARAM_NAME.equalsIgnoreCase(normalizeOptionalText(param.paramName())))
                .map(TcEqpParam::paramValue)
                .map(this::parsePositivePortCount)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private Optional<Integer> findModelPortCount(final long modelVersionKey) {
        int offset = 0;

        while (true) {
            final List<TcModelParam> page = modelParamStore.findAllByModelVersionKey(
                    modelVersionKey,
                    PageRequest.of(offset, MODEL_PARAM_PAGE_LIMIT)
            );
            if (page == null || page.isEmpty()) {
                return Optional.empty();
            }

            final Optional<Integer> portCount = page.stream()
                    .filter(Objects::nonNull)
                    .filter(param -> PORT_COUNT_PARAM_NAME.equalsIgnoreCase(normalizeOptionalText(param.paramName())))
                    .map(TcModelParam::paramValue)
                    .map(this::parsePositivePortCount)
                    .flatMap(Optional::stream)
                    .findFirst();
            if (portCount.isPresent()) {
                return portCount;
            }
            if (page.size() < MODEL_PARAM_PAGE_LIMIT) {
                return Optional.empty();
            }
            offset += MODEL_PARAM_PAGE_LIMIT;
        }
    }

    private Optional<Integer> parsePositivePortCount(final String rawValue) {
        final String normalized = normalizeOptionalText(rawValue);
        if (normalized == null) {
            return Optional.empty();
        }

        try {
            final int parsed = Integer.parseInt(normalized);
            return parsed > 0 ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
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
            final UiProtocolType interfaceType,
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

        if (interfaceType == UiProtocolType.SECS) {
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

    private void restoreProtocolDetails(final long eqpKey, final EqpManagementSnapshot snapshot) {
        if (snapshot.eqp().commInterface() == UiProtocolType.SECS && snapshot.hsms() != null) {
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

        if (snapshot.eqp().commInterface() == UiProtocolType.SOCKET && snapshot.socket() != null) {
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
                UiDomainEnumMapper.toDbLogLevel(snapshot.logPolicy().logLevel()),
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
                UiDomainEnumMapper.toDbControlState(snapshot.runtimeState().controlState()),
                UiDomainEnumMapper.toDbEqpState(snapshot.runtimeState().eqpState()),
                snapshot.runtimeState().sinceAt(),
                snapshot.runtimeState().reasonCode(),
                snapshot.runtimeState().reasonDetail(),
                snapshot.runtimeState().updatedAt()
        ));
    }

    private void restorePortStatuses(final long eqpKey, final List<UiEqpPortStatus> snapshotPortStatuses) {
        final List<UiEqpPortStatus> existingPortStatuses = dbSupport.loadAllPortStatuses(eqpKey);
        for (UiEqpPortStatus existing : existingPortStatuses) {
            eqpPortStatusStore.deleteByEqpKeyPortId(eqpKey, existing.portId());
        }

        for (UiEqpPortStatus status : snapshotPortStatuses) {
            eqpPortStatusStore.upsert(new UpsertTcEqpPortStatus(
                    eqpKey,
                    status.portId(),
                    UiDomainEnumMapper.toDbPortType(status.portType()),
                    status.portState(),
                    status.carrierId(),
                    UiDomainEnumMapper.toDbCarrierType(status.carrierType()),
                    UiDomainEnumMapper.toDbCarrierState(status.carrierState()),
                    status.updatedAt()
            ));
        }
    }

    private void restoreParams(final long eqpKey, final List<UiEqpParam> snapshotParams) {
        final List<UiEqpParam> existingParams = dbSupport.loadAllParams(eqpKey);
        for (UiEqpParam existing : existingParams) {
            eqpParamStore.deleteByEqpParamKey(existing.eqpParamKey());
        }

        for (UiEqpParam param : snapshotParams) {
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

    private void restoreParamVersionMetas(final long eqpKey, final List<UiEqpParamVersion> snapshotParamVersionMetas) {
        eqpParamVersionStore.deleteAllByEqpKey(eqpKey);

        for (UiEqpParamVersion paramVersionMeta : snapshotParamVersionMetas) {
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
                input == null || input.logLevel() == null ? UiLogLevel.INFO : input.logLevel(),
                input == null || input.logRetentionDays() == null ? 7 : input.logRetentionDays(),
                resolveLogPath(input == null ? null : input.logPath(), "\\")
        );
    }

    private EqpManagementCommand.LogSettings resolveUpdateLogSettings(
            final EqpManagementCommand.LogSettings input,
            final UiEqpLogPolicy existingLogPolicy
    ) {
        final UiLogLevel fallbackLogLevel = existingLogPolicy == null || existingLogPolicy.logLevel() == null
                ? UiLogLevel.INFO
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

    private Long normalizeOptionalJarRef(final Long jarRef, final String fieldName) {
        if (jarRef == null) {
            return null;
        }
        if (jarRef <= 0) {
            throw new UiBadRequestException(fieldName + "는 1 이상이어야 합니다.");
        }
        return jarRef;
    }

    private void validateSelectedJarRefs(final Long gatewayJarRef, final Long businessJarRef) {
        final Long normalizedGatewayJarRef = normalizeOptionalJarRef(gatewayJarRef, "gatewayJarRef");
        final Long normalizedBusinessJarRef = normalizeOptionalJarRef(businessJarRef, "businessJarRef");

        if (normalizedGatewayJarRef != null && jarGatewayStore.findByGatewayJarKey(normalizedGatewayJarRef).isEmpty()) {
            throw new UiBadRequestException("선택한 Gateway Jar를 찾을 수 없습니다. jarRef=" + normalizedGatewayJarRef);
        }
        if (normalizedBusinessJarRef != null && jarBusinessStore.findByBusinessJarKey(normalizedBusinessJarRef).isEmpty()) {
            throw new UiBadRequestException("선택한 Business Jar를 찾을 수 없습니다. jarRef=" + normalizedBusinessJarRef);
        }
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
