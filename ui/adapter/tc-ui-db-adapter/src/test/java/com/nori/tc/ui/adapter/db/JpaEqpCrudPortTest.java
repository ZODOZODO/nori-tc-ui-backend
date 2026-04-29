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
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqp;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqpHsms;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqpLog;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqpPortStatus;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqpSocket;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqpState;
import com.nori.tc.db.core.jar.store.TcJarBusinessStore;
import com.nori.tc.db.core.jar.store.TcJarGatewayStore;
import com.nori.tc.db.core.work.store.TcWorkStore;
import com.nori.tc.db.domain.common.eqp.ControlState;
import com.nori.tc.db.domain.common.eqp.EqpState;
import com.nori.tc.db.domain.common.eqp.LogLevel;
import com.nori.tc.db.domain.common.model.ProtocolType;
import com.nori.tc.db.domain.eqp.TcEqp;
import com.nori.tc.db.domain.eqp.TcEqpParam;
import com.nori.tc.db.domain.jar.TcJarBusiness;
import com.nori.tc.db.domain.jar.TcJarGateway;
import com.nori.tc.db.domain.model.TcModelParam;
import com.nori.tc.ui.adapter.db.mapper.UiDomainEnumMapper;
import com.nori.tc.ui.adapter.db.mapper.UiEqpDbMapper;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.port.db.DemoJobCleanupPort;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JpaEqpCrudPort}의 EQP 생성 규칙을 검증합니다.
 */
class JpaEqpCrudPortTest {

    @Test
    @DisplayName("SECS EQP 생성 초기 상태는 DOWN/DOWN으로 생성됩니다")
    void createInitialStateCommandForSecsUsesDownDown() {
        final OffsetDateTime fixedTimestamp = OffsetDateTime.parse("2026-03-11T10:15:30+09:00");

        final UpsertTcEqpState command = JpaEqpCrudPort.createInitialStateCommand(
                100L,
                UiProtocolType.SECS,
                fixedTimestamp
        );

        assertEquals(100L, command.eqpKey());
        assertEquals(ControlState.DOWN, command.controlState());
        assertEquals(EqpState.DOWN, command.eqpState());
        assertEquals(fixedTimestamp, command.sinceAt());
        assertEquals("EQP_CREATED", command.reasonCode());
        assertEquals("EQP created by UI management service", command.reasonDetail());
        assertEquals(fixedTimestamp, command.updatedAt());
    }

    @Test
    @DisplayName("SECS EQP 생성은 공통/SECS/log/state row를 함께 저장합니다")
    void createSecsEqpPersistsCommonProtocolAndInitialRows() {
        final Fixture fixture = new Fixture();
        final EqpManagementCommand.Create command = new EqpManagementCommand.Create(
                "tester",
                "EQP-SECS-001",
                UiProtocolType.SECS,
                "active",
                true,
                3,
                "10.10.0.1",
                5000,
                101L,
                null,
                null,
                null,
                null,
                new EqpManagementCommand.HsmsSettings(null, null, null, null, null, null, null, null, null),
                null
        );
        final TcEqp storedEqp = eqp(
                11L,
                command.eqpId(),
                UiDomainEnumMapper.toDbProtocolType(command.interfaceType()),
                "ACTIVE",
                command.isDev(),
                command.routePartition(),
                command.eqpIp(),
                command.eqpPort(),
                command.modelVersionKey(),
                "tester"
        );
        final EqpManagementSnapshot snapshot = snapshot(storedEqp);

        when(fixture.eqpStore.upsert(any())).thenReturn(storedEqp);
        when(fixture.dbSupport.loadSnapshotByEqpId(command.eqpId())).thenReturn(Optional.of(snapshot));

        final EqpManagementSnapshot result = fixture.port.create(command);

        assertSame(snapshot, result);

        final ArgumentCaptor<UpsertTcEqp> eqpCaptor = ArgumentCaptor.forClass(UpsertTcEqp.class);
        verify(fixture.eqpStore).upsert(eqpCaptor.capture());
        assertEquals(command.eqpId(), eqpCaptor.getValue().eqpId());
        assertEquals(UiDomainEnumMapper.toDbProtocolType(command.interfaceType()), eqpCaptor.getValue().commInterface());
        assertEquals("ACTIVE", eqpCaptor.getValue().commMode());
        assertEquals(command.isDev(), eqpCaptor.getValue().isDev());
        assertEquals(command.routePartition(), eqpCaptor.getValue().routePartition());
        assertEquals(command.eqpIp(), eqpCaptor.getValue().eqpIp());
        assertEquals(command.eqpPort(), eqpCaptor.getValue().eqpPort());
        assertEquals(command.modelVersionKey(), eqpCaptor.getValue().modelVersionKey());
        assertEquals(true, eqpCaptor.getValue().enabled());

        final ArgumentCaptor<UpsertTcEqpHsms> hsmsCaptor = ArgumentCaptor.forClass(UpsertTcEqpHsms.class);
        verify(fixture.eqpHsmsStore).upsert(hsmsCaptor.capture());
        assertEquals(11L, hsmsCaptor.getValue().eqpKey());
        assertEquals(0, hsmsCaptor.getValue().deviceId());
        assertEquals(45, hsmsCaptor.getValue().t3Timeout());
        assertEquals(10, hsmsCaptor.getValue().t5Timeout());
        assertEquals(5, hsmsCaptor.getValue().t6Timeout());
        assertEquals(10, hsmsCaptor.getValue().t7Timeout());
        assertEquals(5, hsmsCaptor.getValue().t8Timeout());
        assertEquals(true, hsmsCaptor.getValue().linkTestEnabled());
        assertEquals(60, hsmsCaptor.getValue().linkTestInterval());
        assertEquals(10_485_760L, hsmsCaptor.getValue().maxMsgBytes());
        verify(fixture.eqpSocketStore).deleteByEqpKey(11L);

        final ArgumentCaptor<UpsertTcEqpLog> logCaptor = ArgumentCaptor.forClass(UpsertTcEqpLog.class);
        verify(fixture.eqpLogStore).upsert(logCaptor.capture());
        assertEquals(11L, logCaptor.getValue().eqpKey());
        assertEquals(LogLevel.INFO, logCaptor.getValue().logLevel());
        assertEquals(7, logCaptor.getValue().logRetentionDays());
        assertEquals("\\", logCaptor.getValue().logPath());

        final ArgumentCaptor<UpsertTcEqpState> stateCaptor = ArgumentCaptor.forClass(UpsertTcEqpState.class);
        verify(fixture.eqpStateStore).upsert(stateCaptor.capture());
        assertEquals(11L, stateCaptor.getValue().eqpKey());
        assertEquals(ControlState.DOWN, stateCaptor.getValue().controlState());
        assertEquals(EqpState.DOWN, stateCaptor.getValue().eqpState());
        assertEquals("EQP_CREATED", stateCaptor.getValue().reasonCode());

        final ArgumentCaptor<UpsertTcEqpPortStatus> portStatusCaptor = ArgumentCaptor.forClass(UpsertTcEqpPortStatus.class);
        verify(fixture.eqpPortStatusStore, org.mockito.Mockito.times(4)).upsert(portStatusCaptor.capture());
        assertEquals(List.of("1", "2", "3", "4"), portStatusCaptor.getAllValues().stream().map(UpsertTcEqpPortStatus::portId).toList());
        assertEquals(List.of("down", "down", "down", "down"), portStatusCaptor.getAllValues().stream().map(UpsertTcEqpPortStatus::portState).toList());
    }

    @Test
    @DisplayName("EQP 생성은 eqp parameter PORTCOUNT 값을 우선해 초기 포트를 생성합니다")
    void createEqpUsesEqpParameterPortCountFirst() {
        final Fixture fixture = new Fixture();
        final EqpManagementCommand.Create command = new EqpManagementCommand.Create(
                "tester",
                "EQP-PORTCOUNT-EQP",
                UiProtocolType.SECS,
                "ACTIVE",
                true,
                3,
                "10.10.0.1",
                5000,
                101L,
                "v1",
                null,
                null,
                null,
                new EqpManagementCommand.HsmsSettings(null, null, null, null, null, null, null, null, null),
                null
        );
        final TcEqp storedEqp = eqp(
                12L,
                command.eqpId(),
                UiDomainEnumMapper.toDbProtocolType(command.interfaceType()),
                "ACTIVE",
                command.isDev(),
                command.routePartition(),
                command.eqpIp(),
                command.eqpPort(),
                command.modelVersionKey(),
                "tester",
                "v1"
        );

        when(fixture.eqpStore.upsert(any())).thenReturn(storedEqp);
        when(fixture.dbSupport.loadSnapshotByEqpId(command.eqpId())).thenReturn(Optional.of(snapshot(storedEqp)));
        when(fixture.eqpParamStore.findAllByEqpKeyAndVersion(12L, "v1")).thenReturn(List.of(
                eqpParam(12L, "v1", "PORTCOUNT", "3")
        ));

        fixture.port.create(command);

        final ArgumentCaptor<UpsertTcEqpPortStatus> portStatusCaptor = ArgumentCaptor.forClass(UpsertTcEqpPortStatus.class);
        verify(fixture.eqpPortStatusStore, org.mockito.Mockito.times(3)).upsert(portStatusCaptor.capture());
        assertEquals(List.of("1", "2", "3"), portStatusCaptor.getAllValues().stream().map(UpsertTcEqpPortStatus::portId).toList());
        assertEquals(List.of("down", "down", "down"), portStatusCaptor.getAllValues().stream().map(UpsertTcEqpPortStatus::portState).toList());
    }

    @Test
    @DisplayName("EQP 생성은 eqp parameter가 없으면 model parameter PORTCOUNT 값을 사용합니다")
    void createEqpFallsBackToModelParameterPortCount() {
        final Fixture fixture = new Fixture();
        final EqpManagementCommand.Create command = new EqpManagementCommand.Create(
                "tester",
                "EQP-PORTCOUNT-MODEL",
                UiProtocolType.SECS,
                "ACTIVE",
                true,
                3,
                "10.10.0.1",
                5000,
                101L,
                "v1",
                null,
                null,
                null,
                new EqpManagementCommand.HsmsSettings(null, null, null, null, null, null, null, null, null),
                null
        );
        final TcEqp storedEqp = eqp(
                13L,
                command.eqpId(),
                UiDomainEnumMapper.toDbProtocolType(command.interfaceType()),
                "ACTIVE",
                command.isDev(),
                command.routePartition(),
                command.eqpIp(),
                command.eqpPort(),
                command.modelVersionKey(),
                "tester",
                "v1"
        );

        when(fixture.eqpStore.upsert(any())).thenReturn(storedEqp);
        when(fixture.dbSupport.loadSnapshotByEqpId(command.eqpId())).thenReturn(Optional.of(snapshot(storedEqp)));
        when(fixture.eqpParamStore.findAllByEqpKeyAndVersion(13L, "v1")).thenReturn(List.of());
        when(fixture.modelParamStore.findAllByModelVersionKey(eq(101L), any())).thenReturn(List.of(
                modelParam(101L, "PORTCOUNT", "2")
        ));

        fixture.port.create(command);

        final ArgumentCaptor<UpsertTcEqpPortStatus> portStatusCaptor = ArgumentCaptor.forClass(UpsertTcEqpPortStatus.class);
        verify(fixture.eqpPortStatusStore, org.mockito.Mockito.times(2)).upsert(portStatusCaptor.capture());
        assertEquals(List.of("1", "2"), portStatusCaptor.getAllValues().stream().map(UpsertTcEqpPortStatus::portId).toList());
    }

    @Test
    @DisplayName("EQP 생성은 eqp/model parameter 모두 없으면 포트 4개를 기본 생성합니다")
    void createEqpUsesDefaultPortCountWhenParametersMissing() {
        final Fixture fixture = new Fixture();
        final EqpManagementCommand.Create command = new EqpManagementCommand.Create(
                "tester",
                "EQP-PORTCOUNT-DEFAULT",
                UiProtocolType.SECS,
                "ACTIVE",
                true,
                3,
                "10.10.0.1",
                5000,
                101L,
                null,
                null,
                null,
                null,
                new EqpManagementCommand.HsmsSettings(null, null, null, null, null, null, null, null, null),
                null
        );
        final TcEqp storedEqp = eqp(
                14L,
                command.eqpId(),
                UiDomainEnumMapper.toDbProtocolType(command.interfaceType()),
                "ACTIVE",
                command.isDev(),
                command.routePartition(),
                command.eqpIp(),
                command.eqpPort(),
                command.modelVersionKey(),
                "tester"
        );

        when(fixture.eqpStore.upsert(any())).thenReturn(storedEqp);
        when(fixture.dbSupport.loadSnapshotByEqpId(command.eqpId())).thenReturn(Optional.of(snapshot(storedEqp)));
        when(fixture.modelParamStore.findAllByModelVersionKey(eq(101L), any())).thenReturn(List.of());

        fixture.port.create(command);

        final ArgumentCaptor<UpsertTcEqpPortStatus> portStatusCaptor = ArgumentCaptor.forClass(UpsertTcEqpPortStatus.class);
        verify(fixture.eqpPortStatusStore, org.mockito.Mockito.times(4)).upsert(portStatusCaptor.capture());
        assertEquals(List.of("1", "2", "3", "4"), portStatusCaptor.getAllValues().stream().map(UpsertTcEqpPortStatus::portId).toList());
    }

    @Test
    @DisplayName("SOCKET EQP 생성은 공통/SOCKET/log/state row를 함께 저장합니다")
    void createSocketEqpPersistsCommonProtocolAndInitialRows() {
        final Fixture fixture = new Fixture();
        final EqpManagementCommand.Create command = new EqpManagementCommand.Create(
                "tester",
                "EQP-SOCKET-001",
                UiProtocolType.SOCKET,
                "PASSIVE",
                false,
                5,
                "10.20.0.1",
                6100,
                201L,
                null,
                null,
                null,
                null,
                null,
                new EqpManagementCommand.SocketSettings("JSON", null, null, null, null, null, null, null)
        );
        final TcEqp storedEqp = eqp(
                21L,
                command.eqpId(),
                UiDomainEnumMapper.toDbProtocolType(command.interfaceType()),
                command.commMode(),
                command.isDev(),
                command.routePartition(),
                command.eqpIp(),
                command.eqpPort(),
                command.modelVersionKey(),
                "tester"
        );

        when(fixture.eqpStore.upsert(any())).thenReturn(storedEqp);
        when(fixture.dbSupport.loadSnapshotByEqpId(command.eqpId())).thenReturn(Optional.of(snapshot(storedEqp)));

        fixture.port.create(command);

        final ArgumentCaptor<UpsertTcEqpSocket> socketCaptor = ArgumentCaptor.forClass(UpsertTcEqpSocket.class);
        verify(fixture.eqpSocketStore).upsert(socketCaptor.capture());
        assertEquals(21L, socketCaptor.getValue().eqpKey());
        assertEquals("JSON", socketCaptor.getValue().socketProtocolType());
        assertEquals("UTF-8", socketCaptor.getValue().charset());
        assertEquals(true, socketCaptor.getValue().heartbeatEnabled());
        assertEquals(30, socketCaptor.getValue().heartbeatInterval());
        assertEquals(0, socketCaptor.getValue().readTimeout());
        assertEquals(0, socketCaptor.getValue().writeTimeout());
        assertEquals(8192, socketCaptor.getValue().maxFrameSizeBytes());
        assertEquals(true, socketCaptor.getValue().keepAliveEnabled());
        verify(fixture.eqpHsmsStore).deleteByEqpKey(21L);

        final ArgumentCaptor<UpsertTcEqpLog> logCaptor = ArgumentCaptor.forClass(UpsertTcEqpLog.class);
        verify(fixture.eqpLogStore).upsert(logCaptor.capture());
        assertEquals(LogLevel.INFO, logCaptor.getValue().logLevel());
        assertEquals(7, logCaptor.getValue().logRetentionDays());
        assertEquals("\\", logCaptor.getValue().logPath());

        final ArgumentCaptor<UpsertTcEqpState> stateCaptor = ArgumentCaptor.forClass(UpsertTcEqpState.class);
        verify(fixture.eqpStateStore).upsert(stateCaptor.capture());
        assertEquals(ControlState.DISCONNECTED, stateCaptor.getValue().controlState());
        assertEquals(EqpState.SERVICE_UNAVAILABLE, stateCaptor.getValue().eqpState());
    }

    @Test
    @DisplayName("EQP 생성은 선택한 jar ref를 tc_eqp에 그대로 저장합니다")
    void createEqpPersistsSelectedJarRefs() {
        final Fixture fixture = new Fixture();
        final EqpManagementCommand.Create command = new EqpManagementCommand.Create(
                "tester",
                "EQP-JAR-001",
                UiProtocolType.SECS,
                "ACTIVE",
                true,
                1,
                "127.0.0.1",
                5000,
                101L,
                null,
                999L,
                998L,
                null,
                new EqpManagementCommand.HsmsSettings(1, 45, 10, 5, 10, 5, true, 60, 10_485_760L),
                null
        );
        final TcEqp storedEqp = eqp(
                31L,
                command.eqpId(),
                UiDomainEnumMapper.toDbProtocolType(command.interfaceType()),
                command.commMode(),
                command.isDev(),
                command.routePartition(),
                command.eqpIp(),
                command.eqpPort(),
                command.modelVersionKey(),
                "tester",
                null,
                command.gatewayJarRef(),
                command.businessJarRef()
        );
        final TcJarGateway gatewaySource = new TcJarGateway(
                999L,
                "gateway-main.jar",
                new byte[]{1, 2, 3},
                OffsetDateTime.parse("2026-03-10T10:15:30+09:00"),
                OffsetDateTime.parse("2026-03-11T10:15:30+09:00"),
                "SYSTEM",
                "SYSTEM"
        );
        final TcJarBusiness businessSource = new TcJarBusiness(
                998L,
                "business-main.jar",
                new byte[]{7, 8, 9},
                OffsetDateTime.parse("2026-03-09T10:15:30+09:00"),
                OffsetDateTime.parse("2026-03-11T11:15:30+09:00"),
                "SYSTEM",
                "SYSTEM"
        );

        when(fixture.eqpStore.upsert(any())).thenReturn(storedEqp);
        when(fixture.dbSupport.loadSnapshotByEqpId(command.eqpId())).thenReturn(Optional.of(snapshot(storedEqp)));
        when(fixture.jarGatewayStore.findByGatewayJarKey(999L)).thenReturn(Optional.of(gatewaySource));
        when(fixture.jarBusinessStore.findByBusinessJarKey(998L)).thenReturn(Optional.of(businessSource));

        fixture.port.create(command);

        final ArgumentCaptor<UpsertTcEqp> eqpCaptor = ArgumentCaptor.forClass(UpsertTcEqp.class);
        verify(fixture.eqpStore).upsert(eqpCaptor.capture());
        assertEquals(999L, eqpCaptor.getValue().gatewayJarKey());
        assertEquals(998L, eqpCaptor.getValue().businessJarKey());
        verify(fixture.jarGatewayStore).findByGatewayJarKey(999L);
        verify(fixture.jarBusinessStore).findByBusinessJarKey(998L);
        verify(fixture.jarGatewayStore, never()).upsert(any());
        verify(fixture.jarBusinessStore, never()).upsert(any());
    }

    @Test
    @DisplayName("EQP 수정은 commMode와 appliedParamVersion을 공통 row에 반영합니다")
    @SuppressWarnings("unchecked")
    void updateEqpPersistsCommModeAndAppliedParamVersion() {
        final Fixture fixture = new Fixture();
        final String eqpId = "EQP-UPDATE-001";
        final EqpManagementCommand.Update command = new EqpManagementCommand.Update(
                "tester",
                "PASSIVE",
                true,
                2,
                "10.10.0.2",
                5100,
                202L,
                "v3",
                null,
                null,
                null,
                new EqpManagementCommand.HsmsSettings(0, 45, 10, 5, 10, 5, true, 60, 10_485_760L),
                null
        );
        final EqpManagementSnapshot existingSnapshot = snapshot(eqp(
                41L,
                eqpId,
                ProtocolType.SECS,
                "ACTIVE",
                false,
                1,
                "10.10.0.1",
                5000,
                101L,
                "SYSTEM",
                null
        ));
        final TcEqp updatedEqp = eqp(
                41L,
                eqpId,
                ProtocolType.SECS,
                "PASSIVE",
                true,
                2,
                "10.10.0.2",
                5100,
                202L,
                "tester",
                "v3"
        );
        final EqpManagementSnapshot updatedSnapshot = snapshot(updatedEqp);

        when(fixture.dbSupport.loadSnapshotByEqpId(eqpId))
                .thenReturn(Optional.of(existingSnapshot), Optional.of(updatedSnapshot));
        when(fixture.eqpStore.upsert(any())).thenReturn(updatedEqp);

        final EqpManagementSnapshot result = fixture.port.update(eqpId, command);

        assertSame(updatedSnapshot, result);

        final ArgumentCaptor<UpsertTcEqp> eqpCaptor = ArgumentCaptor.forClass(UpsertTcEqp.class);
        verify(fixture.eqpStore).upsert(eqpCaptor.capture());
        assertEquals("PASSIVE", eqpCaptor.getValue().commMode());
        assertEquals("v3", eqpCaptor.getValue().appliedParamVersion());
        assertEquals(existingSnapshot.eqp().createdBy(), eqpCaptor.getValue().createdBy());
        assertEquals("tester", eqpCaptor.getValue().updatedBy());
    }

    @Test
    @DisplayName("SOCKET EQP 생성 초기 상태는 DISCONNECTED/SERVICE_UNAVAILABLE로 생성됩니다")
    void createInitialStateCommandForSocketUsesDisconnectedServiceUnavailable() {
        final OffsetDateTime fixedTimestamp = OffsetDateTime.parse("2026-03-11T10:15:30+09:00");

        final UpsertTcEqpState command = JpaEqpCrudPort.createInitialStateCommand(
                200L,
                UiProtocolType.SOCKET,
                fixedTimestamp
        );

        assertEquals(200L, command.eqpKey());
        assertEquals(ControlState.DISCONNECTED, command.controlState());
        assertEquals(EqpState.SERVICE_UNAVAILABLE, command.eqpState());
        assertEquals(fixedTimestamp, command.sinceAt());
        assertEquals("EQP_CREATED", command.reasonCode());
        assertEquals("EQP created by UI management service", command.reasonDetail());
        assertEquals(fixedTimestamp, command.updatedAt());
    }

    @Test
    @DisplayName("EQP 삭제는 tc_work를 먼저 정리한 뒤 tc_eqp를 삭제합니다")
    void deleteEqpRemovesWorksBeforeEqpDelete() {
        final Fixture fixture = new Fixture();
        final String eqpId = "EQP-DELETE-001";
        final EqpManagementSnapshot snapshot = snapshot(eqp(
                51L,
                eqpId,
                ProtocolType.SOCKET,
                "PASSIVE",
                false,
                2,
                "127.0.0.1",
                6000,
                301L,
                "tester"
        ));

        when(fixture.dbSupport.loadSnapshotByEqpId(eqpId)).thenReturn(Optional.of(snapshot));

        fixture.port.delete(eqpId);

        final var inOrder = inOrder(fixture.workStore, fixture.eqpStore);
        inOrder.verify(fixture.workStore).deleteAllByEqpKey(51L);
        inOrder.verify(fixture.eqpStore).deleteByEqpId(eq(eqpId));
    }

    @Test
    @DisplayName("SOCKET EQP 생성은 listener-group routePartition 충돌 시 저장 전에 차단합니다")
    void createSocketEqpRejectsPassiveListenerConflictBeforePersist() {
        final Fixture fixture = new Fixture();
        final EqpManagementCommand.Create command = new EqpManagementCommand.Create(
                "tester",
                "CVD_DEMO_01",
                UiProtocolType.SOCKET,
                "PASSIVE",
                false,
                2,
                "127.0.0.1",
                6100,
                201L,
                null,
                null,
                null,
                null,
                null,
                new EqpManagementCommand.SocketSettings("LINE_DELIMITED", null, null, null, null, null, null, null)
        );

        when(fixture.dbSupport.findPassiveListenerGroupRoutePartitionConflict(
                eq(UiProtocolType.SOCKET),
                eq((String) null),
                eq(command.eqpIp()),
                eq(command.eqpPort()),
                eq(command.routePartition()),
                eq("LINE_DELIMITED")
        )).thenReturn(Optional.of("DEMO_SOCKET01"));

        assertThrows(UiBadRequestException.class, () -> fixture.port.create(command));

        verify(fixture.eqpStore, never()).upsert(any());
        verify(fixture.eqpSocketStore, never()).upsert(any());
    }

    @Test
    @DisplayName("SECS EQP 수정은 listener-group routePartition 충돌 시 저장 전에 차단합니다")
    void updateSecsEqpRejectsPassiveListenerConflictBeforePersist() {
        final Fixture fixture = new Fixture();
        final String eqpId = "EQP-SECS-CONFLICT-001";
        final EqpManagementCommand.Update command = new EqpManagementCommand.Update(
                "tester",
                "PASSIVE",
                true,
                7,
                "10.10.0.5",
                5000,
                301L,
                null,
                null,
                null,
                null,
                new EqpManagementCommand.HsmsSettings(0, 45, 10, 5, 10, 5, true, 60, 10_485_760L),
                null
        );
        final EqpManagementSnapshot existingSnapshot = snapshot(eqp(
                61L,
                eqpId,
                ProtocolType.SECS,
                "ACTIVE",
                false,
                1,
                "10.10.0.1",
                5000,
                101L,
                "SYSTEM"
        ));

        when(fixture.dbSupport.loadSnapshotByEqpId(eqpId)).thenReturn(Optional.of(existingSnapshot));
        when(fixture.dbSupport.findPassiveListenerGroupRoutePartitionConflict(
                eq(UiProtocolType.SECS),
                eq(eqpId),
                eq(command.eqpIp()),
                eq(command.eqpPort()),
                eq(command.routePartition()),
                eq((String) null)
        )).thenReturn(Optional.of("PEER-SECS-001"));

        assertThrows(UiBadRequestException.class, () -> fixture.port.update(eqpId, command));

        verify(fixture.eqpStore, never()).upsert(any());
        verify(fixture.eqpHsmsStore, never()).upsert(any());
    }

    /**
     * 테스트 대상 포트와 mock 의존성을 함께 묶습니다.
     */
    private static final class Fixture {

        private final EqpManagementDbSupport dbSupport = mock(EqpManagementDbSupport.class);
        private final TcEqpStore eqpStore = mock(TcEqpStore.class);
        private final TcEqpHsmsStore eqpHsmsStore = mock(TcEqpHsmsStore.class);
        private final TcEqpSocketStore eqpSocketStore = mock(TcEqpSocketStore.class);
        private final TcEqpLogStore eqpLogStore = mock(TcEqpLogStore.class);
        private final TcEqpStateStore eqpStateStore = mock(TcEqpStateStore.class);
        private final TcEqpPortStatusStore eqpPortStatusStore = mock(TcEqpPortStatusStore.class);
        private final TcEqpParamStore eqpParamStore = mock(TcEqpParamStore.class);
        private final TcEqpParamVersionStore eqpParamVersionStore = mock(TcEqpParamVersionStore.class);
        private final TcModelParamStore modelParamStore = mock(TcModelParamStore.class);
        private final TcJarGatewayStore jarGatewayStore = mock(TcJarGatewayStore.class);
        private final TcJarBusinessStore jarBusinessStore = mock(TcJarBusinessStore.class);
        private final TcWorkStore workStore = mock(TcWorkStore.class);
        private final DemoJobCleanupPort demoJobCleanupPort = mock(DemoJobCleanupPort.class);
        private final JpaEqpCrudPort port = new JpaEqpCrudPort(
                dbSupport,
                eqpStore,
                eqpHsmsStore,
                eqpSocketStore,
                eqpLogStore,
                eqpStateStore,
                eqpPortStatusStore,
                eqpParamStore,
                eqpParamVersionStore,
                modelParamStore,
                jarGatewayStore,
                jarBusinessStore,
                workStore,
                demoJobCleanupPort
        );
    }

    private static TcEqp eqp(
            final long eqpKey,
            final String eqpId,
            final ProtocolType protocolType,
            final String commMode,
            final boolean isDev,
            final int routePartition,
            final String eqpIp,
            final int eqpPort,
            final long modelVersionKey,
            final String actor
    ) {
        return eqp(
                eqpKey,
                eqpId,
                protocolType,
                commMode,
                isDev,
                routePartition,
                eqpIp,
                eqpPort,
                modelVersionKey,
                actor,
                null,
                null,
                null
        );
    }

    private static TcEqp eqp(
            final long eqpKey,
            final String eqpId,
            final ProtocolType protocolType,
            final String commMode,
            final boolean isDev,
            final int routePartition,
            final String eqpIp,
            final int eqpPort,
            final long modelVersionKey,
            final String actor,
            final String appliedParamVersion
    ) {
        return eqp(
                eqpKey,
                eqpId,
                protocolType,
                commMode,
                isDev,
                routePartition,
                eqpIp,
                eqpPort,
                modelVersionKey,
                actor,
                appliedParamVersion,
                null,
                null
        );
    }

    private static TcEqp eqp(
            final long eqpKey,
            final String eqpId,
            final ProtocolType protocolType,
            final String commMode,
            final boolean isDev,
            final int routePartition,
            final String eqpIp,
            final int eqpPort,
            final long modelVersionKey,
            final String actor,
            final String appliedParamVersion,
            final Long gatewayJarKey,
            final Long businessJarKey
    ) {
        final OffsetDateTime now = OffsetDateTime.parse("2026-03-11T10:15:30+09:00");
        return new TcEqp(
                eqpKey,
                eqpId,
                protocolType,
                commMode,
                isDev,
                routePartition,
                eqpIp,
                eqpPort,
                modelVersionKey,
                appliedParamVersion,
                gatewayJarKey,
                businessJarKey,
                true,
                now,
                now,
                actor,
                actor
        );
    }

    private static EqpManagementSnapshot snapshot(final TcEqp eqp) {
        return new EqpManagementSnapshot(
                UiEqpDbMapper.toDomain(eqp),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null
        );
    }

    private static TcEqpParam eqpParam(
            final long eqpKey,
            final String version,
            final String paramName,
            final String paramValue
    ) {
        return new TcEqpParam(
                1L,
                eqpKey,
                paramName,
                version,
                paramValue,
                null,
                "tester",
                OffsetDateTime.parse("2026-03-11T10:15:30+09:00")
        );
    }

    private static TcModelParam modelParam(
            final long modelVersionKey,
            final String paramName,
            final String paramValue
    ) {
        return new TcModelParam(
                1L,
                modelVersionKey,
                paramName,
                paramValue,
                null,
                OffsetDateTime.parse("2026-03-11T10:15:30+09:00")
        );
    }
}
