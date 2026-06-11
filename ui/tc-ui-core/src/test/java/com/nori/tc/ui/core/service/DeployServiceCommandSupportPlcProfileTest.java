package com.nori.tc.ui.core.service;

import tools.jackson.databind.ObjectMapper;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.model.UiEquipmentProfileSnapshot;
import com.nori.tc.ui.core.port.db.DeployStorePort;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.core.port.db.ModelCrudPort;
import com.nori.tc.ui.core.port.db.ModelDetailQueryPort;
import com.nori.tc.ui.core.port.messaging.UiBusinessEventPublishPort;
import com.nori.tc.ui.core.port.messaging.UiGatewayEventPublishPort;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpPlc;
import com.nori.tc.ui.domain.eqp.UiEqpRuntimeState;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * L1337(create 경로) — direct create/lifecycle 스냅샷 빌더가 PLC 설정을 운반하는지 검증.
 *
 * <p>{@code DeployServiceCommandSupport.buildDirectEquipmentProfile(executionSnapshot)}(빌더 ⓐ full-populate)가
 * {@code EqpManagementSnapshot.plc()}(ⓒ)를 읽어 {@code UiEquipmentProfileSnapshot.plcSettings} non-null + desiredState 를
 * 채우는지 고정한다. 이 Ui 스냅샷을 {@code UiKafkaContractMapper}(ⓓ, 별 테스트)가 Gateway 스냅샷으로 매핑하므로,
 * 두 테스트가 합쳐 create 경로 end-to-end(레코드 필드 추가만 하고 빌더 누락 시 plcSettings==null) 회귀를 포착한다.</p>
 */
class DeployServiceCommandSupportPlcProfileTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DeployServiceCommandSupport newSupport() {
        return new DeployServiceCommandSupport(
                mock(DeployStorePort.class),
                mock(EqpCrudPort.class),
                mock(EqpQueryPort.class),
                mock(ModelCrudPort.class),
                mock(ModelDetailQueryPort.class),
                mock(UiGatewayEventPublishPort.class),
                mock(UiBusinessEventPublishPort.class),
                objectMapper
        );
    }

    @Test
    void directEquipmentProfileCarriesPlcSettingsAndDesiredStateFromSnapshot() {
        final DeployServiceCommandSupport support = newSupport();
        final EqpManagementSnapshot snapshot = plcSnapshot("STARTED");

        final UiEquipmentProfileSnapshot profile = support.buildDirectEquipmentProfile(
                UiCommandEventType.EQP_CREATE,
                snapshot,
                objectMapper.createObjectNode()
        );

        assertNotNull(profile);
        // 빌더 ⓐ+ⓒ: snapshot.plc() → plcSettings non-null + 필드 운반.
        assertNotNull(profile.plcSettings());
        assertEquals("MODBUS_TCP", profile.plcSettings().plcProtocol());
        assertEquals("TCP", profile.plcSettings().transport());
        assertEquals(3, profile.plcSettings().unitId());
        assertEquals(3000, profile.plcSettings().pollTimeoutMs());
        assertEquals(5, profile.plcSettings().reconnectMaxRetries());
        assertEquals(List.of("EQP_STATUS_REPORT"), profile.plcSettings().enabledPollGroups());
        // desiredState 는 runtimeState 에서 운반(null→ENDED 가 아니라 실제 STARTED).
        assertEquals("STARTED", profile.desiredState());
    }

    @Test
    void neverStartedPlcSnapshotDefaultsDesiredStateToEnded() {
        final DeployServiceCommandSupport support = newSupport();
        final EqpManagementSnapshot snapshot = plcSnapshot(null);

        final UiEquipmentProfileSnapshot profile = support.buildDirectEquipmentProfile(
                UiCommandEventType.EQP_CREATE,
                snapshot,
                objectMapper.createObjectNode()
        );

        assertNotNull(profile.plcSettings());
        assertEquals("ENDED", profile.desiredState());
    }

    private EqpManagementSnapshot plcSnapshot(final String desiredState) {
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        final UiEqp eqp = new UiEqp(
                7L, "EQP-PLC-01", 1L, UiProtocolType.PLC, "ACTIVE", false, 1,
                "10.30.0.1", 502, 500L, null, null, null, true, now, now, "SYSTEM", "SYSTEM"
        );
        final UiEqpPlc plc = new UiEqpPlc(
                7L, "MODBUS_TCP", "TCP", 3, 3000, 5, List.of("EQP_STATUS_REPORT"), 0L, now, now
        );
        final UiEqpRuntimeState runtimeState = new UiEqpRuntimeState(
                7L, null, null, desiredState, now, null, null, now
        );
        return new EqpManagementSnapshot(
                eqp, null, null, null, plc, null, runtimeState, null,
                List.of(), List.of(), List.of(), null, null
        );
    }
}
