package com.nori.tc.ui.adapters.kafka.publish;

import com.nori.tc.messaging.kafka.contract.GatewayEquipmentProfileSnapshot;
import com.nori.tc.ui.core.model.UiEquipmentProfileSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link UiKafkaContractMapper} 의 desiredState / plcSettings 운반 검증.
 *
 * <ul>
 *   <li>L1333 — {@code desiredState=STARTED} 가 {@link GatewayEquipmentProfileSnapshot} 로 운반(enabled 만이 아니라 DB 의도 운반).</li>
 *   <li>L1337(ⓓ) — Ui {@code plcSettings} → Gateway {@code plcSettings} non-null 매핑(스냅샷 빌더 4곳 중 매퍼 site).</li>
 * </ul>
 */
class UiKafkaContractMapperTest {

    @Test
    void carriesDesiredStateStartedAndPlcSettingsToGatewaySnapshot() {
        final UiEquipmentProfileSnapshot.PlcRegisterSnapshot uiRegister =
                new UiEquipmentProfileSnapshot.PlcRegisterSnapshot(
                        0, "TEMP", "UINT16", null, null, null, "BIG_ENDIAN", "BIG_ENDIAN", 0.1, null, null, "온도");
        final UiEquipmentProfileSnapshot.PlcGroupSnapshot uiGroup =
                new UiEquipmentProfileSnapshot.PlcGroupSnapshot(
                        "EQP_STATUS_REPORT", "HOLDING_REGISTER", 100, 4, "0x", 1000, "상태", List.of(uiRegister));
        final UiEquipmentProfileSnapshot ui = profile(
                "STARTED",
                new UiEquipmentProfileSnapshot.PlcSettingsSnapshot(
                        "MODBUS_TCP", "TCP", 3, 3000, 5, List.of("EQP_STATUS_REPORT"), List.of(uiGroup))
        );

        final GatewayEquipmentProfileSnapshot result = UiKafkaContractMapper.toGatewayEquipmentProfileSnapshot(ui);

        // L1333: enabled 가 아니라 DB desired_state 운반.
        assertEquals("STARTED", result.desiredState());

        // L1337(ⓓ): plcSettings non-null + 필드 1:1 매핑.
        assertNotNull(result.plcSettings());
        assertEquals("MODBUS_TCP", result.plcSettings().plcProtocol());
        assertEquals("TCP", result.plcSettings().transport());
        assertEquals(3, result.plcSettings().unitId());
        assertEquals(3000, result.plcSettings().pollTimeoutMs());
        assertEquals(5, result.plcSettings().reconnectMaxRetries());
        assertEquals(List.of("EQP_STATUS_REPORT"), result.plcSettings().enabledPollGroups());
        // AC6: 디코드 스펙(groups)을 계약으로 운반(빈 리스트 아님) + 그룹/레지스터 필드 1:1 매핑.
        assertEquals(1, result.plcSettings().groups().size());
        final GatewayEquipmentProfileSnapshot.PlcGroupSnapshot group = result.plcSettings().groups().getFirst();
        assertEquals("EQP_STATUS_REPORT", group.messageName());
        assertEquals("HOLDING_REGISTER", group.area());
        assertEquals(100, group.startAddress());
        assertEquals(4, group.readCount());
        assertEquals(1000, group.pollPeriodMs());
        assertEquals(1, group.registers().size());
        final GatewayEquipmentProfileSnapshot.PlcRegisterSnapshot register = group.registers().getFirst();
        assertEquals(0, register.wordOffset());
        assertEquals("TEMP", register.variableName());
        assertEquals("UINT16", register.dataType());
        assertEquals(0.1, register.scale());
    }

    @Test
    void nonPlcSnapshotMapsToNullGatewayPlcSettingsAndEndedDesiredState() {
        final UiEquipmentProfileSnapshot ui = profile("ENDED", null);

        final GatewayEquipmentProfileSnapshot result = UiKafkaContractMapper.toGatewayEquipmentProfileSnapshot(ui);

        assertEquals("ENDED", result.desiredState());
        assertNull(result.plcSettings());
    }

    private static UiEquipmentProfileSnapshot profile(
            final String desiredState,
            final UiEquipmentProfileSnapshot.PlcSettingsSnapshot plcSettings
    ) {
        return new UiEquipmentProfileSnapshot(
                7L,
                "EQP-PLC-01",
                "PLC",
                null,
                null,
                "10.30.0.1",
                502,
                500L,
                null,
                null,
                "ACTIVE",
                1,
                true,
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                "2026-06-10T00:00:00Z",
                desiredState,
                plcSettings
        );
    }
}
