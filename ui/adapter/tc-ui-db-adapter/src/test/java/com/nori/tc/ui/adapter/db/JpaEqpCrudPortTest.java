package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.eqp.store.TcEqpHsmsStore;
import com.nori.tc.db.core.eqp.store.TcEqpLogStore;
import com.nori.tc.db.core.eqp.store.TcEqpParamStore;
import com.nori.tc.db.core.eqp.store.TcEqpParamVersionStore;
import com.nori.tc.db.core.eqp.store.TcEqpPlcStore;
import com.nori.tc.db.core.eqp.store.TcEqpPortStatusStore;
import com.nori.tc.db.core.eqp.store.TcEqpSocketStore;
import com.nori.tc.db.core.eqp.store.TcEqpStateStore;
import com.nori.tc.db.core.eqp.store.TcEqpStore;
import com.nori.tc.db.core.model.store.TcModelParamStore;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqp;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqpHsms;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqpLog;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqpPlc;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqpPortStatus;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqpSocket;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqpState;
import com.nori.tc.db.domain.common.model.PlcProtocol;
import com.nori.tc.db.domain.common.model.PlcTransport;
import com.nori.tc.db.core.jar.store.TcJarBusinessStore;
import com.nori.tc.db.core.jar.store.TcJarGatewayStore;
import com.nori.tc.db.core.line.store.TcLineStore;
import com.nori.tc.db.core.work.store.TcWorkStore;
import com.nori.tc.db.domain.common.eqp.ControlState;
import com.nori.tc.db.domain.common.eqp.DesiredState;
import com.nori.tc.db.domain.common.eqp.EqpState;
import com.nori.tc.db.domain.common.eqp.LogLevel;
import com.nori.tc.db.domain.common.model.ProtocolType;
import com.nori.tc.db.domain.eqp.TcEqp;
import com.nori.tc.db.domain.eqp.TcEqpParam;
import com.nori.tc.db.domain.line.TcLine;
import com.nori.tc.db.domain.jar.TcJarBusiness;
import com.nori.tc.db.domain.jar.TcJarGateway;
import com.nori.tc.db.domain.model.TcModelParam;
import com.nori.tc.ui.adapter.db.mapper.UiDomainEnumMapper;
import com.nori.tc.ui.adapter.db.mapper.UiEqpDbMapper;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.domain.common.eqp.UiControlState;
import com.nori.tc.ui.domain.common.eqp.UiEqpState;
import com.nori.tc.ui.domain.eqp.UiEqpRuntimeState;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.port.db.DemoJobCleanupPort;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.eqp.UiEqpPortStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
                1L,
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
                null,
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
        assertEquals(List.of("DOWN", "DOWN", "DOWN", "DOWN"), portStatusCaptor.getAllValues().stream().map(UpsertTcEqpPortStatus::portState).toList());
    }

    @Test
    @DisplayName("EQP 생성은 eqp parameter PORTCOUNT 값을 우선해 초기 포트를 생성합니다")
    void createEqpUsesEqpParameterPortCountFirst() {
        final Fixture fixture = new Fixture();
        final EqpManagementCommand.Create command = new EqpManagementCommand.Create(
                "tester",
                "EQP-PORTCOUNT-EQP",
                1L,
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
                null,
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
        assertEquals(List.of("DOWN", "DOWN", "DOWN"), portStatusCaptor.getAllValues().stream().map(UpsertTcEqpPortStatus::portState).toList());
    }

    @Test
    @DisplayName("EQP 생성은 eqp parameter가 없으면 model parameter PORTCOUNT 값을 사용합니다")
    void createEqpFallsBackToModelParameterPortCount() {
        final Fixture fixture = new Fixture();
        final EqpManagementCommand.Create command = new EqpManagementCommand.Create(
                "tester",
                "EQP-PORTCOUNT-MODEL",
                1L,
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
                null,
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
                1L,
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
                null,
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
    @DisplayName("S08 D-1: EQP 수정은 스냅샷을 읽기 전에 tc_eqp 행을 배타 잠금으로 선점합니다(lost update 차단)")
    void updateAcquiresRowLockBeforeReadingSnapshot() {
        final Fixture fixture = new Fixture();
        final String eqpId = "ETC-UPD-LOCK";
        when(fixture.dbSupport.loadSnapshotByEqpId(eqpId))
                .thenReturn(Optional.of(snapshot(existingSecsEqp(eqpId, "v1", 101L))));
        when(fixture.eqpStore.upsert(any())).thenReturn(updatedSecsEqp(eqpId, "v1", 101L));

        fixture.port.update(eqpId, updateCommand("v1", 101L));

        // 잠금 → 읽기 → 쓰기 순서가 깨지면 두 편집이 서로의 변경분을 stale read 로 덮어쓴다.
        final org.mockito.InOrder ordered = inOrder(fixture.eqpStore, fixture.dbSupport);
        ordered.verify(fixture.eqpStore).findByEqpIdForUpdate(eqpId);
        ordered.verify(fixture.dbSupport).loadSnapshotByEqpId(eqpId);
        ordered.verify(fixture.eqpStore).upsert(any());
    }

    @Test
    @DisplayName("EQP 수정으로 파라미터 버전이 바뀌면 PORTCOUNT 초과 포트를 제거하고 겹치는 포트는 보존합니다")
    void updateSyncsPortStatusesDownToPortCount() {
        final Fixture fixture = new Fixture();
        final String eqpId = "ETC-UPD-DOWN";
        when(fixture.dbSupport.loadSnapshotByEqpId(eqpId))
                .thenReturn(Optional.of(snapshot(existingSecsEqp(eqpId, "v1", 101L))));
        when(fixture.eqpStore.upsert(any())).thenReturn(updatedSecsEqp(eqpId, "v2", 101L));
        when(fixture.dbSupport.loadAllPortStatuses(12L)).thenReturn(List.of(
                portStatus(12L, "1"), portStatus(12L, "2"), portStatus(12L, "3"), portStatus(12L, "4")));
        when(fixture.eqpParamStore.findAllByEqpKeyAndVersion(12L, "v2"))
                .thenReturn(List.of(eqpParam(12L, "v2", "PORTCOUNT", "1")));

        fixture.port.update(eqpId, updateCommand("v2", 101L));

        verify(fixture.eqpPortStatusStore).deleteByEqpKeyPortId(12L, "2");
        verify(fixture.eqpPortStatusStore).deleteByEqpKeyPortId(12L, "3");
        verify(fixture.eqpPortStatusStore).deleteByEqpKeyPortId(12L, "4");
        verify(fixture.eqpPortStatusStore, never()).deleteByEqpKeyPortId(12L, "1");
        verify(fixture.eqpPortStatusStore, never()).upsert(any());
    }

    @Test
    @DisplayName("EQP 수정으로 PORTCOUNT가 늘면 부족한 포트만 추가하고 기존 포트는 재생성하지 않습니다")
    void updateSyncsPortStatusesUpToPortCount() {
        final Fixture fixture = new Fixture();
        final String eqpId = "ETC-UPD-UP";
        when(fixture.dbSupport.loadSnapshotByEqpId(eqpId))
                .thenReturn(Optional.of(snapshot(existingSecsEqp(eqpId, "v1", 101L))));
        when(fixture.eqpStore.upsert(any())).thenReturn(updatedSecsEqp(eqpId, "v2", 101L));
        when(fixture.dbSupport.loadAllPortStatuses(12L)).thenReturn(List.of(portStatus(12L, "1")));
        when(fixture.eqpParamStore.findAllByEqpKeyAndVersion(12L, "v2"))
                .thenReturn(List.of(eqpParam(12L, "v2", "PORTCOUNT", "3")));

        fixture.port.update(eqpId, updateCommand("v2", 101L));

        final ArgumentCaptor<UpsertTcEqpPortStatus> captor = ArgumentCaptor.forClass(UpsertTcEqpPortStatus.class);
        verify(fixture.eqpPortStatusStore, org.mockito.Mockito.times(2)).upsert(captor.capture());
        assertEquals(List.of("2", "3"), captor.getAllValues().stream().map(UpsertTcEqpPortStatus::portId).toList());
        verify(fixture.eqpPortStatusStore, never()).deleteByEqpKeyPortId(anyLong(), any());
    }

    @Test
    @DisplayName("EQP 수정 시 PORTCOUNT가 1 미만이면 거부하고 port_status를 건드리지 않습니다")
    void updateRejectsPortCountBelowOne() {
        final Fixture fixture = new Fixture();
        final String eqpId = "ETC-UPD-ZERO";
        when(fixture.dbSupport.loadSnapshotByEqpId(eqpId))
                .thenReturn(Optional.of(snapshot(existingSecsEqp(eqpId, "v1", 101L))));
        when(fixture.eqpStore.upsert(any())).thenReturn(updatedSecsEqp(eqpId, "v2", 101L));
        when(fixture.eqpParamStore.findAllByEqpKeyAndVersion(12L, "v2"))
                .thenReturn(List.of(eqpParam(12L, "v2", "PORTCOUNT", "0")));

        assertThrows(UiBadRequestException.class, () -> fixture.port.update(eqpId, updateCommand("v2", 101L)));

        verify(fixture.eqpPortStatusStore, never()).deleteByEqpKeyPortId(anyLong(), any());
        verify(fixture.eqpPortStatusStore, never()).upsert(any());
    }

    @Test
    @DisplayName("EQP 수정에서 모델/파라미터 버전이 그대로면 port_status를 재동기화하지 않습니다")
    void updateWithoutVersionChangeLeavesPortStatusesUntouched() {
        final Fixture fixture = new Fixture();
        final String eqpId = "ETC-UPD-NOOP";
        when(fixture.dbSupport.loadSnapshotByEqpId(eqpId))
                .thenReturn(Optional.of(snapshot(existingSecsEqp(eqpId, "v1", 101L))));
        when(fixture.eqpStore.upsert(any())).thenReturn(updatedSecsEqp(eqpId, "v1", 101L));

        fixture.port.update(eqpId, updateCommand("v1", 101L));

        verify(fixture.dbSupport, never()).loadAllPortStatuses(anyLong());
        verify(fixture.eqpPortStatusStore, never()).deleteByEqpKeyPortId(anyLong(), any());
        verify(fixture.eqpPortStatusStore, never()).upsert(any());
    }

    @Test
    @DisplayName("SOCKET EQP 생성은 공통/SOCKET/log/state row를 함께 저장합니다")
    void createSocketEqpPersistsCommonProtocolAndInitialRows() {
        final Fixture fixture = new Fixture();
        final EqpManagementCommand.Create command = new EqpManagementCommand.Create(
                "tester",
                "EQP-SOCKET-001",
                1L,
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
                new EqpManagementCommand.SocketSettings("JSON", null, null, null, null, null, null, null),
                null
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
        // 신규 생성은 통신 방향과 무관하게 desired_state=ENDED(R-E2/R-E3 — PASSIVE=STARTED seed 폐기).
        assertEquals(DesiredState.ENDED, stateCaptor.getValue().desiredState());
    }

    @Test
    @DisplayName("PLC EQP 생성은 tc_eqp_plc에 저장하고 socket/hsms store에는 저장하지 않습니다")
    void createPlcEqpPersistsPlcAndDeletesHsmsSocket() {
        final Fixture fixture = new Fixture();
        final EqpManagementCommand.Create command = new EqpManagementCommand.Create(
                "tester",
                "EQP-PLC-001",
                1L,
                UiProtocolType.PLC,
                "ACTIVE",
                false,
                1,
                "10.30.0.1",
                502,
                301L,
                null,
                null,
                null,
                null,
                null,
                null,
                new EqpManagementCommand.PlcSettings("MODBUS_TCP", "TCP", 3, 3000, 5)
        );
        final TcEqp storedEqp = eqp(
                51L,
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

        fixture.port.create(command);

        final ArgumentCaptor<UpsertTcEqpPlc> plcCaptor = ArgumentCaptor.forClass(UpsertTcEqpPlc.class);
        verify(fixture.eqpPlcStore).upsert(plcCaptor.capture());
        assertEquals(51L, plcCaptor.getValue().eqpKey());
        assertEquals(PlcProtocol.MODBUS_TCP, plcCaptor.getValue().plcProtocol());
        assertEquals(PlcTransport.TCP, plcCaptor.getValue().transport());
        assertEquals(3, plcCaptor.getValue().unitId());
        assertEquals(3000, plcCaptor.getValue().pollTimeoutMs());
        assertEquals(5, plcCaptor.getValue().reconnectMaxRetries());
        // 신규 설비는 폴링 그룹 0 → 빈 집합. enabled_poll_groups 는 GROUP_START/END saga 가 관리한다.
        assertEquals("[]", plcCaptor.getValue().enabledPollGroups());

        // 이분 분기 오저장 회귀 차단: PLC 는 socket/hsms store 에 절대 upsert 하지 않고 정리(delete)만 한다.
        verify(fixture.eqpSocketStore, never()).upsert(any());
        verify(fixture.eqpHsmsStore, never()).upsert(any());
        verify(fixture.eqpHsmsStore).deleteByEqpKey(51L);
        verify(fixture.eqpSocketStore).deleteByEqpKey(51L);
    }

    @Test
    @DisplayName("PLC EQP 생성은 plcSettings 누락 시 저장 전에 400으로 거부합니다")
    void createPlcEqpRejectsMissingPlcSettings() {
        final Fixture fixture = new Fixture();
        final EqpManagementCommand.Create command = new EqpManagementCommand.Create(
                "tester",
                "EQP-PLC-NULL",
                1L,
                UiProtocolType.PLC,
                "ACTIVE",
                false,
                1,
                "10.30.0.1",
                502,
                301L,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThrows(UiBadRequestException.class, () -> fixture.port.create(command));
        verify(fixture.eqpStore, never()).upsert(any());
        verify(fixture.eqpPlcStore, never()).upsert(any());
    }

    @Test
    @DisplayName("PLC EQP 생성도 tc_eqp_port_status를 PORTCOUNT(미지정=기본 4)만큼 초기화합니다")
    void createPlcEqpInitializesDefaultFourPortStatuses() {
        final Fixture fixture = new Fixture();
        final EqpManagementCommand.Create command = new EqpManagementCommand.Create(
                "tester",
                "EQP-PLC-PORT",
                1L,
                UiProtocolType.PLC,
                "ACTIVE",
                false,
                1,
                "10.30.0.9",
                502,
                301L,
                null,
                null,
                null,
                null,
                null,
                null,
                new EqpManagementCommand.PlcSettings("MODBUS_TCP", "TCP", 1, 3000, 3)
        );
        final TcEqp storedEqp = eqp(
                52L,
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

        fixture.port.create(command);

        // upsertInitialPortStatuses 는 프로토콜 무관 무조건 호출(§6) — PORTCOUNT 미지정이면 기본 4.
        // "PLC=포트 없음" 배제 회귀 차단: SECS/SOCKET 과 동일하게 4 행 생성.
        final ArgumentCaptor<UpsertTcEqpPortStatus> portStatusCaptor = ArgumentCaptor.forClass(UpsertTcEqpPortStatus.class);
        verify(fixture.eqpPortStatusStore, org.mockito.Mockito.times(4)).upsert(portStatusCaptor.capture());
        assertEquals(List.of("1", "2", "3", "4"), portStatusCaptor.getAllValues().stream().map(UpsertTcEqpPortStatus::portId).toList());
    }

    @Test
    @DisplayName("EQP 생성은 선택한 jar ref를 tc_eqp에 그대로 저장합니다")
    void createEqpPersistsSelectedJarRefs() {
        final Fixture fixture = new Fixture();
        final EqpManagementCommand.Create command = new EqpManagementCommand.Create(
                "tester",
                "EQP-JAR-001",
                1L,
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
                null,
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
                1L,
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
                null,
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
    @DisplayName("PLC EQP 수정은 update DTO의 plcSettings를 tc_eqp_plc에 반영하고 socket/hsms는 정리합니다(프로토콜 불변)")
    @SuppressWarnings("unchecked")
    void updatePlcEqpAppliesPlcSettingsFromUpdateDto() {
        final Fixture fixture = new Fixture();
        final String eqpId = "EQP-PLC-UPDATE";
        // 요청에 interfaceType 없음 — 프로토콜 불변(§7). plcSettings 만 갱신(unitId 1→7, timeout 3000→5000, retries 3→2).
        final EqpManagementCommand.Update command = new EqpManagementCommand.Update(
                "tester",
                1L,
                "ACTIVE",
                false,
                1,
                "10.30.0.2",
                502,
                301L,
                null,
                null,
                null,
                null,
                null,
                null,
                new EqpManagementCommand.PlcSettings("MODBUS_TCP", "TCP", 7, 5000, 2)
        );
        final EqpManagementSnapshot existingSnapshot = snapshot(eqp(
                61L,
                eqpId,
                ProtocolType.PLC,
                "ACTIVE",
                false,
                1,
                "10.30.0.1",
                502,
                301L,
                "SYSTEM",
                null
        ));
        final TcEqp updatedEqp = eqp(
                61L,
                eqpId,
                ProtocolType.PLC,
                "ACTIVE",
                false,
                1,
                "10.30.0.2",
                502,
                301L,
                "tester",
                null
        );
        final EqpManagementSnapshot updatedSnapshot = snapshot(updatedEqp);

        when(fixture.dbSupport.loadSnapshotByEqpId(eqpId))
                .thenReturn(Optional.of(existingSnapshot), Optional.of(updatedSnapshot));
        when(fixture.eqpStore.upsert(any())).thenReturn(updatedEqp);

        fixture.port.update(eqpId, command);

        // update DTO 배선: command.plcSettings() 가 upsertProtocolDetails(PLC 분기)로 흘러 tc_eqp_plc 갱신.
        final ArgumentCaptor<UpsertTcEqpPlc> plcCaptor = ArgumentCaptor.forClass(UpsertTcEqpPlc.class);
        verify(fixture.eqpPlcStore).upsert(plcCaptor.capture());
        assertEquals(61L, plcCaptor.getValue().eqpKey());
        assertEquals(PlcProtocol.MODBUS_TCP, plcCaptor.getValue().plcProtocol());
        assertEquals(PlcTransport.TCP, plcCaptor.getValue().transport());
        assertEquals(7, plcCaptor.getValue().unitId());
        assertEquals(5000, plcCaptor.getValue().pollTimeoutMs());
        assertEquals(2, plcCaptor.getValue().reconnectMaxRetries());

        // 3-way: PLC 갱신 시 socket/hsms 는 upsert 안 하고 정리만(이분 분기 오저장 회귀 차단).
        verify(fixture.eqpSocketStore, never()).upsert(any());
        verify(fixture.eqpHsmsStore, never()).upsert(any());
        verify(fixture.eqpHsmsStore).deleteByEqpKey(61L);
        verify(fixture.eqpSocketStore).deleteByEqpKey(61L);
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
    @DisplayName("AC-A5: restore 경로는 desired_state=null(never-started)을 ENDED 로 승격하지 않고 null 로 보존합니다")
    void restorePreservesNullDesiredState() {
        final Fixture fixture = new Fixture();
        final TcEqp passiveEqp = eqp(30L, "EQP-PASSIVE-NULL", ProtocolType.SOCKET, "PASSIVE",
                false, 1, "127.0.0.1", 6000, 1L, "SYSTEM");
        when(fixture.eqpStore.upsert(any())).thenReturn(passiveEqp);

        // desired_state=null(PASSIVE never-started) 인 runtimeState 를 담은 복구 스냅샷.
        final UiEqpRuntimeState runtimeState = new UiEqpRuntimeState(
                30L, UiControlState.OFFLINE, UiEqpState.IDLE, null,
                OffsetDateTime.parse("2026-03-11T10:15:30+09:00"), "R", "r",
                OffsetDateTime.parse("2026-03-11T10:15:30+09:00"));
        final EqpManagementSnapshot snapshot = new EqpManagementSnapshot(
                UiEqpDbMapper.toDomain(passiveEqp), null, null, null, null, null, runtimeState, null,
                List.of(), List.of(), List.of(), null, null);

        fixture.port.restore(snapshot);

        final ArgumentCaptor<UpsertTcEqpState> stateCaptor = ArgumentCaptor.forClass(UpsertTcEqpState.class);
        verify(fixture.eqpStateStore).upsert(stateCaptor.capture());
        // 핵심: null→ENDED 무음 승격 없이 null 보존.
        assertNull(stateCaptor.getValue().desiredState());
    }

    @Test
    @DisplayName("신규 설비 생성 초기 desired_state는 통신 방향과 무관하게 ENDED입니다(R-E2/R-E3 — PASSIVE=STARTED seed 폐기)")
    void createInitialStateCommandAlwaysUsesEndedDesiredState() {
        final OffsetDateTime fixedTimestamp = OffsetDateTime.parse("2026-03-11T10:15:30+09:00");

        // PASSIVE 도 ENDED — PASSIVE listener 는 desired 가 아니라 enabled 로 개방(R-E1 gateway)하므로 seed STARTED 불요.
        final UpsertTcEqpState passiveCommand = JpaEqpCrudPort.createInitialStateCommand(
                300L,
                UiProtocolType.SOCKET,
                fixedTimestamp
        );
        assertEquals(DesiredState.ENDED, passiveCommand.desiredState());

        // ACTIVE 도 ENDED — STARTED 전이는 EQP_START 시 gateway 가 영속(단일 writer).
        final UpsertTcEqpState activeCommand = JpaEqpCrudPort.createInitialStateCommand(
                301L,
                UiProtocolType.SOCKET,
                fixedTimestamp
        );
        assertEquals(DesiredState.ENDED, activeCommand.desiredState());
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
                1L,
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
                new EqpManagementCommand.SocketSettings("LINE_DELIMITED", null, null, null, null, null, null, null),
                null
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
                1L,
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
                null,
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

    @Test
    @DisplayName("EQP 생성은 미등록 line_key면 저장 전에 거부합니다")
    void createEqpRejectsUnregisteredLineKey() {
        final Fixture fixture = new Fixture();
        // 사전 등록되지 않은 라인 → findByKey 빈 결과
        when(fixture.lineStore.findByKey(anyLong())).thenReturn(Optional.empty());

        final EqpManagementCommand.Create command = new EqpManagementCommand.Create(
                "tester",
                "EQP-LINE-REJECT-001",
                999L,
                UiProtocolType.SECS,
                "ACTIVE",
                true,
                1,
                "10.10.0.1",
                5000,
                101L,
                null,
                null,
                null,
                null,
                new EqpManagementCommand.HsmsSettings(null, null, null, null, null, null, null, null, null),
                null,
                null
        );

        assertThrows(UiBadRequestException.class, () -> fixture.port.create(command));

        verify(fixture.eqpStore, never()).upsert(any());
    }

    /**
     * 테스트 대상 포트와 mock 의존성을 함께 묶습니다.
     */
    private static final class Fixture {

        private final EqpManagementDbSupport dbSupport = mock(EqpManagementDbSupport.class);
        private final TcEqpStore eqpStore = mock(TcEqpStore.class);
        private final TcEqpHsmsStore eqpHsmsStore = mock(TcEqpHsmsStore.class);
        private final TcEqpSocketStore eqpSocketStore = mock(TcEqpSocketStore.class);
        private final TcEqpPlcStore eqpPlcStore = mock(TcEqpPlcStore.class);
        private final TcEqpLogStore eqpLogStore = mock(TcEqpLogStore.class);
        private final TcEqpStateStore eqpStateStore = mock(TcEqpStateStore.class);
        private final TcEqpPortStatusStore eqpPortStatusStore = mock(TcEqpPortStatusStore.class);
        private final TcEqpParamStore eqpParamStore = mock(TcEqpParamStore.class);
        private final TcEqpParamVersionStore eqpParamVersionStore = mock(TcEqpParamVersionStore.class);
        private final TcModelParamStore modelParamStore = mock(TcModelParamStore.class);
        private final TcJarGatewayStore jarGatewayStore = mock(TcJarGatewayStore.class);
        private final TcJarBusinessStore jarBusinessStore = mock(TcJarBusinessStore.class);
        private final TcLineStore lineStore = mock(TcLineStore.class);
        private final TcWorkStore workStore = mock(TcWorkStore.class);
        private final DemoJobCleanupPort demoJobCleanupPort = mock(DemoJobCleanupPort.class);
        private final JpaEqpCrudPort port = new JpaEqpCrudPort(
                dbSupport,
                eqpStore,
                eqpHsmsStore,
                eqpSocketStore,
                eqpPlcStore,
                eqpLogStore,
                eqpStateStore,
                eqpPortStatusStore,
                eqpParamStore,
                eqpParamVersionStore,
                modelParamStore,
                jarGatewayStore,
                jarBusinessStore,
                lineStore,
                workStore,
                demoJobCleanupPort
        );

        // 기본 stub: 사전 등록된 라인을 반환한다. 미등록 거부 시나리오는 테스트에서 Optional.empty()로 재정의한다.
        {
            when(lineStore.findByKey(anyLong()))
                    .thenReturn(Optional.of(new TcLine(1L, "A_LINE", "A Line", null, true, null, null)));
        }
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
                1L,
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
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null
        );
    }

    private static TcEqp existingSecsEqp(final String eqpId, final String appliedParamVersion, final long modelVersionKey) {
        return eqp(12L, eqpId, ProtocolType.SECS, "ACTIVE", true, 3, "10.10.0.1", 5000,
                modelVersionKey, "SYSTEM", appliedParamVersion);
    }

    private static TcEqp updatedSecsEqp(final String eqpId, final String appliedParamVersion, final long modelVersionKey) {
        return eqp(12L, eqpId, ProtocolType.SECS, "ACTIVE", true, 3, "10.10.0.1", 5000,
                modelVersionKey, "tester", appliedParamVersion);
    }

    private static EqpManagementCommand.Update updateCommand(final String appliedParamVersion, final long modelVersionKey) {
        return new EqpManagementCommand.Update(
                "tester", 1L, "ACTIVE", true, 3, "10.10.0.1", 5000,
                modelVersionKey, appliedParamVersion, null, null, null,
                new EqpManagementCommand.HsmsSettings(0, 45, 10, 5, 10, 5, true, 60, 10_485_760L),
                null, null);
    }

    private static UiEqpPortStatus portStatus(final long eqpKey, final String portId) {
        return new UiEqpPortStatus(0L, eqpKey, portId, null, "DOWN", null, null, null,
                OffsetDateTime.parse("2026-03-11T10:15:30+09:00"));
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
