package com.nori.tc.ui.adapters.web.simulator;

import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.service.EqpManagementService;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpPortStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * AC-5 [R-3 연결해소] — eqpId → tc_eqp(comm_interface/comm_mode/eqp_port) 조회 → createRun target 변환 정확
 * (C1 SOCKET/PASSIVE/6100 ∧ C2 SOCKET/ACTIVE/6600 ∧ C3 PLC/ACTIVE/7101, 3 케이스 AND). eqp_ip override 0(DB 실값).
 */
@ExtendWith(MockitoExtension.class)
class SimulatorTargetAssemblerTest {

    @Mock
    private EqpManagementService eqpManagementService;

    @Test
    void resolvesSocketPassiveActiveAndPlcTargetsFromRealDbConfig() {
        when(eqpManagementService.getManageDetail("CLN_001"))
                .thenReturn(Optional.of(snapshot(eqp("CLN_001", UiProtocolType.SOCKET, "PASSIVE", 6100))));
        when(eqpManagementService.getManageDetail("DIFF_001"))
                .thenReturn(Optional.of(snapshot(eqp("DIFF_001", UiProtocolType.SOCKET, "ACTIVE", 6600))));
        when(eqpManagementService.getManageDetail("PLC_MBT_001"))
                .thenReturn(Optional.of(snapshot(eqp("PLC_MBT_001", UiProtocolType.PLC, "ACTIVE", 7101))));
        final SimulatorTargetAssembler assembler = new SimulatorTargetAssembler(eqpManagementService);

        // C1 — SOCKET/PASSIVE/6100 (시뮬레이터 dial-out).
        final SimulatorCreateRunRequest.SimulatorTargetRequest c1 = assembler.toTarget("CLN_001");
        assertEquals("CLN_001", c1.eqpId());
        assertEquals("SOCKET", c1.commInterface(), "C1 comm_interface");
        assertEquals("PASSIVE", c1.commMode(), "C1 comm_mode(gateway listen → 시뮬레이터 dial)");
        assertEquals(6100, c1.bindPort(), "C1 eqp_port 실값(override 0)");
        assertEquals(List.of("1"), c1.portIds());

        // C2 — SOCKET/ACTIVE/6600 (시뮬레이터 server bind).
        final SimulatorCreateRunRequest.SimulatorTargetRequest c2 = assembler.toTarget("DIFF_001");
        assertEquals("SOCKET", c2.commInterface(), "C2 comm_interface");
        assertEquals("ACTIVE", c2.commMode(), "C2 comm_mode(gateway dial → 시뮬레이터 bind)");
        assertEquals(6600, c2.bindPort(), "C2 eqp_port 실값");

        // C3 — PLC/ACTIVE/7101 (Modbus slave bind).
        final SimulatorCreateRunRequest.SimulatorTargetRequest c3 = assembler.toTarget("PLC_MBT_001");
        assertEquals("PLC", c3.commInterface(), "C3 comm_interface=PLC");
        assertEquals("ACTIVE", c3.commMode(), "C3 comm_mode(gateway master poll)");
        assertEquals(7101, c3.bindPort(), "C3 eqp_port 실값");
    }

    @Test
    void missingEquipmentThrowsNotFound() {
        when(eqpManagementService.getManageDetail("GHOST")).thenReturn(Optional.empty());
        final SimulatorTargetAssembler assembler = new SimulatorTargetAssembler(eqpManagementService);
        assertThrows(UiNotFoundException.class, () -> assembler.toTarget("GHOST"));
    }

    private static UiEqp eqp(final String eqpId, final UiProtocolType commInterface, final String commMode, final int eqpPort) {
        return new UiEqp(
                1L, eqpId, 1L, commInterface, commMode, false, null, "192.168.0.13", eqpPort, 63L,
                "v1", null, null, true, null, null, "tester", "tester");
    }

    private static EqpManagementSnapshot snapshot(final UiEqp eqp) {
        final UiEqpPortStatus port = new UiEqpPortStatus(1L, 1L, "1", null, "IDLE", null, null, null, null);
        return new EqpManagementSnapshot(
                eqp, null, null, null, null, null, null, null, List.of(port), null, null, null, null);
    }
}
