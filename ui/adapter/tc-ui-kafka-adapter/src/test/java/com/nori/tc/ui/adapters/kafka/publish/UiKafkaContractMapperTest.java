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
        final UiEquipmentProfileSnapshot ui = profile(
                "STARTED",
                new UiEquipmentProfileSnapshot.PlcSettingsSnapshot(
                        "MODBUS_TCP", "TCP", 3, 3000, 5, List.of("EQP_STATUS_REPORT"))
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
        // 디코드 스펙(groups)은 gateway 가 tc_model_plc_* DB 직독 → ui-backend 미운반(빈 리스트).
        assertEquals(List.of(), result.plcSettings().groups());
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
