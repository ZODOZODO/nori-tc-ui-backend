package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nori.tc.ui.core.deploy.DeployCurrentState;
import com.nori.tc.ui.core.deploy.DeployTargetPlanDraft;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import com.nori.tc.ui.core.port.db.ModelDetailQueryPort;
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
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AC5 — {@code DeployServiceSnapshotSupport.buildGatewayEquipmentProfile}(pre-state/보상 스냅샷)가
 * {@link ModelDetailQueryPort} 로 디코드 스펙을 조회해 {@code plcSettings.groups} 를 채우는지 고정한다.
 *
 * <p>패키지-사설 {@code buildTargetPreStateSnapshot}(currentState != null → execution snapshot 경로)로 진입한다.</p>
 */
class DeployServiceSnapshotSupportPlcGroupsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final long MODEL_VERSION_KEY = 500L;

    @Test
    void preStateSnapshotFillsPlcGroupsFromModelDetailQueryPort() {
        final EqpCrudPort eqpCrudPort = mock(EqpCrudPort.class);
        final ModelDetailQueryPort modelDetailQueryPort = mock(ModelDetailQueryPort.class);

        when(eqpCrudPort.findSnapshotByEqpId("EQP-PLC-01")).thenReturn(Optional.of(plcSnapshot()));
        when(modelDetailQueryPort.findParamsByModelVersionKey(anyLong())).thenReturn(List.of());
        when(modelDetailQueryPort.findPlcGroupsByModelVersionKey(MODEL_VERSION_KEY)).thenReturn(List.of(sampleGroup()));
        when(modelDetailQueryPort.findPlcRegistersByModelVersionKey(MODEL_VERSION_KEY)).thenReturn(List.of(sampleRegister()));

        final DeployServiceSnapshotSupport support =
                new DeployServiceSnapshotSupport(eqpCrudPort, modelDetailQueryPort, objectMapper);

        final JsonNode preState = support.buildTargetPreStateSnapshot(plcTarget());

        final JsonNode groups = preState.path("equipmentProfile").path("plcSettings").path("groups");
        assertTrue(groups.isArray(), "plcSettings.groups must be an array");
        assertEquals(1, groups.size(), "AC5: groups must be filled from ModelDetailQueryPort (non-empty)");
        assertEquals("EQP_STATUS_REPORT", groups.get(0).path("messageName").asString());
        assertEquals("HOLDING_REGISTER", groups.get(0).path("area").asString());
        assertEquals(1, groups.get(0).path("registers").size());
        assertEquals("TEMP", groups.get(0).path("registers").get(0).path("variableName").asString());
        // AC10(보상 pre-state): gateway-bound modelKey 슬롯 = modelVersionKey(정체성 아님).
        assertEquals(MODEL_VERSION_KEY, preState.path("equipmentProfile").path("modelKey").asLong());
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

    private EqpManagementSnapshot plcSnapshot() {
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        final UiEqp eqp = new UiEqp(
                7L, "EQP-PLC-01", 1L, UiProtocolType.PLC, "ACTIVE", false, 0,
                "10.30.0.1", 502, MODEL_VERSION_KEY, null, null, null, true, now, now, "SYSTEM", "SYSTEM"
        );
        final UiEqpPlc plc = new UiEqpPlc(
                7L, "MODBUS_TCP", "TCP", 3, 3000, 5, List.of("EQP_STATUS_REPORT"), 0L, now, now
        );
        final UiEqpRuntimeState runtimeState = new UiEqpRuntimeState(
                7L, null, null, "STARTED", now, null, null, now
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
