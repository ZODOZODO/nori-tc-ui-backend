package com.nori.tc.ui.core.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.nori.tc.ui.core.deploy.DeployCurrentState;
import com.nori.tc.ui.core.deploy.DeployTargetPlanDraft;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.model.UiEquipmentProfileSnapshot;
import com.nori.tc.ui.core.port.db.DeployStorePort;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.core.port.db.ModelDetailQueryPort;
import com.nori.tc.ui.core.port.messaging.UiBusinessEventPublishPort;
import com.nori.tc.ui.core.port.messaging.UiGatewayEventPublishPort;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpPlc;
import com.nori.tc.ui.domain.eqp.UiEqpRuntimeState;
import com.nori.tc.ui.domain.model.UiModelPlcGroup;
import com.nori.tc.ui.domain.model.UiModelPlcRegister;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * direct/deploy-forward 스냅샷 빌더가 PLC 디코드 스펙(groups)을 운반하고(AC8/AC9) 게이트웨이로 보내는 modelKey 슬롯에
 * modelVersionKey(정체성 아님)를 싣는지(AC10) 고정한다.
 *
 * <p>빌더 ⓐ {@code buildDirectEquipmentProfile(executionSnapshot)}(#3), ⓑ {@code buildDirectEquipmentProfile(eqpKey)}(#2),
 * ⓒ {@code injectPlcGroupsIfPresent}(deploy-forward) 세 경로를 직접 검증한다. ⓓ {@code UiKafkaContractMapper} 매핑은 별 테스트.</p>
 */
class DeployServiceCommandSupportPlcProfileTest {

    private static final long MODEL_VERSION_KEY = 500L;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private DeployServiceCommandSupport newSupport(final ModelDetailQueryPort modelDetailQueryPort) {
        return new DeployServiceCommandSupport(
                mock(DeployStorePort.class),
                mock(EqpCrudPort.class),
                mock(EqpQueryPort.class),
                modelDetailQueryPort,
                mock(UiGatewayEventPublishPort.class),
                mock(UiBusinessEventPublishPort.class),
                objectMapper
        );
    }

    private ModelDetailQueryPort modelDetailQueryPortWithGroups() {
        final ModelDetailQueryPort port = mock(ModelDetailQueryPort.class);
        when(port.findPlcGroupsByModelVersionKey(MODEL_VERSION_KEY)).thenReturn(List.of(sampleGroup()));
        when(port.findPlcRegistersByModelVersionKey(MODEL_VERSION_KEY)).thenReturn(List.of(sampleRegister()));
        return port;
    }

    @Test
    void directEquipmentProfileFromSnapshotCarriesGroupsAndModelVersionKey() {
        // AC9 + AC10(#3): executionSnapshot 빌더가 groups 운반 + modelKey 슬롯 = modelVersionKey.
        final DeployServiceCommandSupport support = newSupport(modelDetailQueryPortWithGroups());
        final EqpManagementSnapshot snapshot = plcSnapshot("STARTED");

        final UiEquipmentProfileSnapshot profile = support.buildDirectEquipmentProfile(
                UiCommandEventType.EQP_CREATE,
                snapshot,
                objectMapper.createObjectNode()
        );

        assertNotNull(profile);
        assertNotNull(profile.plcSettings());
        assertEquals("MODBUS_TCP", profile.plcSettings().plcProtocol());
        assertEquals(List.of("EQP_STATUS_REPORT"), profile.plcSettings().enabledPollGroups());
        assertEquals("STARTED", profile.desiredState());
        // AC9: groups 비어있지 않음.
        assertFalse(profile.plcSettings().groups().isEmpty(), "AC9: direct UPDATE(#3) groups 운반");
        assertEquals("EQP_STATUS_REPORT", profile.plcSettings().groups().get(0).messageName());
        assertEquals(1, profile.plcSettings().groups().get(0).registers().size());
        // AC10(#3): modelKey 슬롯 = modelVersionKey(정체성 아님).
        assertEquals(MODEL_VERSION_KEY, profile.modelKey());
    }

    @Test
    void directEquipmentProfileFromEqpKeyCarriesGroupsAndModelVersionKey() {
        // AC8 + AC10(#2): eqpKey 빌더가 groups 운반 + modelKey 슬롯 = modelVersionKey.
        final DeployServiceCommandSupport support = newSupport(modelDetailQueryPortWithGroups());

        final ObjectNode stepUiMessage = objectMapper.createObjectNode();
        final ObjectNode gatewayProfile = stepUiMessage.putObject("gatewayProfile");
        gatewayProfile.put("plcProtocol", "MODBUS_TCP");
        gatewayProfile.put("transport", "TCP");
        gatewayProfile.put("unitId", 1);
        gatewayProfile.put("pollTimeoutMs", 1000);
        gatewayProfile.put("reconnectMaxRetries", 3);
        gatewayProfile.put("connectionMode", "ACTIVE");
        gatewayProfile.put("eqpIp", "10.30.0.1");
        gatewayProfile.put("eqpPort", 502);
        gatewayProfile.put("desiredState", "ENDED");
        stepUiMessage.putObject("modelInfo").put("modelVersionKey", MODEL_VERSION_KEY);

        final UiEquipmentProfileSnapshot profile = support.buildDirectEquipmentProfile(
                UiCommandEventType.EQP_CREATE,
                "EQP-PLC-01",
                "PLC",
                7L,
                stepUiMessage
        );

        assertNotNull(profile);
        assertNotNull(profile.plcSettings());
        assertFalse(profile.plcSettings().groups().isEmpty(), "AC8: direct CREATE(#2) groups 운반");
        assertEquals("EQP_STATUS_REPORT", profile.plcSettings().groups().get(0).messageName());
        assertEquals(MODEL_VERSION_KEY, profile.modelKey(), "AC10(#2): modelKey 슬롯 = modelVersionKey");
    }

    @Test
    void deployForwardInjectsGroupsAndConvertsModelKeyToModelVersionKey() {
        // AC10(deploy-forward): injectPlcGroupsIfPresent 가 정체성 modelKey 를 modelVersionKey 로 override + groups 주입.
        final DeployServiceCommandSupport support = newSupport(modelDetailQueryPortWithGroups());

        final UiEquipmentProfileSnapshot persisted = new UiEquipmentProfileSnapshot(
                7L, "EQP-PLC-01", "PLC", null, null, "10.30.0.1", 502,
                999L, // 영속 JSON 의 정체성 modelKey(merge/JdbcPort 출처) — forward 시 override 되어야 함.
                null, null, "ACTIVE", 0, true, null, null, null, List.of(), null, List.of(),
                OffsetDateTime.now(ZoneOffset.UTC).toString(), "STARTED",
                new UiEquipmentProfileSnapshot.PlcSettingsSnapshot(
                        "MODBUS_TCP", "TCP", 1, 1000, 3, List.of("EQP_STATUS_REPORT"), List.of())
        );

        final UiEquipmentProfileSnapshot forwarded = support.injectPlcGroupsIfPresent(persisted, plcTarget());

        assertNotNull(forwarded.plcSettings());
        assertFalse(forwarded.plcSettings().groups().isEmpty(), "deploy-forward groups 주입(R6)");
        assertEquals(MODEL_VERSION_KEY, forwarded.modelKey(),
                "AC10(deploy-forward): 정체성 modelKey(999) → modelVersionKey(500) override");
    }

    @Test
    void neverStartedPlcSnapshotDefaultsDesiredStateToEnded() {
        final DeployServiceCommandSupport support = newSupport(mock(ModelDetailQueryPort.class));
        final EqpManagementSnapshot snapshot = plcSnapshot(null);

        final UiEquipmentProfileSnapshot profile = support.buildDirectEquipmentProfile(
                UiCommandEventType.EQP_CREATE,
                snapshot,
                objectMapper.createObjectNode()
        );

        assertNotNull(profile.plcSettings());
        assertEquals("ENDED", profile.desiredState());
    }

    private PersistedDeployTarget plcTarget() {
        final DeployCurrentState currentState = new DeployCurrentState(
                7L, "EQP-PLC-01", "PLC", "ACTIVE", "10.30.0.1", 502, MODEL_VERSION_KEY,
                null, null, null, null, null, 0, null
        );
        final DeployTargetPlanDraft planDraft = new DeployTargetPlanDraft(
                "EQP-PLC-01", "PLC", currentState, null, Set.of(),
                null, null, List.of(), null, null, null, false
        );
        return new PersistedDeployTarget(1L, 0, planDraft);
    }

    private EqpManagementSnapshot plcSnapshot(final String desiredState) {
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        final UiEqp eqp = new UiEqp(
                7L, "EQP-PLC-01", 1L, UiProtocolType.PLC, "ACTIVE", false, 1,
                "10.30.0.1", 502, MODEL_VERSION_KEY, null, null, null, true, now, now, "SYSTEM", "SYSTEM"
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

    private UiModelPlcGroup sampleGroup() {
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new UiModelPlcGroup(
                10L, MODEL_VERSION_KEY, "EQP_STATUS_REPORT", "HOLDING_REGISTER", 100, 4, "0x", 1000, "상태", now
        );
    }

    private UiModelPlcRegister sampleRegister() {
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new UiModelPlcRegister(
                20L, 10L, "EQP_STATUS_REPORT", 0, "TEMP", "UINT16",
                null, null, null, "BIG_ENDIAN", "BIG_ENDIAN", 0.1, null, null, "온도", now
        );
    }
}
