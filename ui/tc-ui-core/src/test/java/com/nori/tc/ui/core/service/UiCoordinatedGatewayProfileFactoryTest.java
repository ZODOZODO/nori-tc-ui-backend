package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
import com.nori.tc.ui.core.coordination.model.UiOperationRequest;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.model.UiEquipmentProfileSnapshot;
import com.nori.tc.ui.core.port.db.DeployStorePort;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import com.nori.tc.ui.core.port.db.EqpManageQueryPort;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.core.port.db.ModelDetailQueryPort;
import com.nori.tc.ui.core.port.messaging.UiBusinessEventPublishPort;
import com.nori.tc.ui.core.port.messaging.UiGatewayEventPublishPort;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpPlc;
import com.nori.tc.ui.domain.eqp.UiEqpSocket;
import com.nori.tc.ui.domain.model.UiModelPlcGroup;
import com.nori.tc.ui.domain.model.UiModelPlcRegister;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §12.3/§12.4 — 조정 경로의 Gateway 명령이 <b>목표 상태</b> profile 을 담는지 고정합니다.
 *
 * <p>Gateway 는 payload 의 {@code equipmentProfile} 이 없으면 context upsert 를
 * {@code EQP_PROFILE_PAYLOAD_REQUIRED} 로 거부하고 DB 에서 대신 읽지도 않습니다. 그리고 조정 경로의
 * DB 확정은 commit <b>이후</b>이므로 PREPARE/APPLY 시점에 DB 를 읽으면 변경 전 값이 나옵니다 —
 * 그래서 목표 상태는 원장 manifest 의 DB 명령에서 합성해야 합니다.</p>
 */
class UiCoordinatedGatewayProfileFactoryTest {

    private static final String EQP_ID = "EQP001";
    private static final long MODEL_VERSION_KEY = 500L;
    private static final long NEW_MODEL_VERSION_KEY = 501L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("수정 operation 의 Gateway profile 은 DB 가 아니라 manifest 의 목표 값을 담는다")
    void updateProfileCarriesTheManifestTargetInsteadOfTheCurrentRow() {
        final UiEquipmentProfileSnapshot profile = factory(socketSnapshot(), mock(ModelDetailQueryPort.class))
                .profileFor(updateManifest(updateCommand("10.0.0.9", 6100, "ACTIVE", MODEL_VERSION_KEY)));

        assertNotNull(profile, "Gateway 는 profile 이 없으면 EQP_PROFILE_PAYLOAD_REQUIRED 로 거부합니다.");
        assertEquals("10.0.0.9", profile.eqpIp());
        assertEquals(6100, profile.eqpPort());
        assertEquals("ACTIVE", profile.connectionMode());
        // Gateway 는 eqpKey 를 양수로 요구한다(GatewayUiRuntimeControlSupport).
        assertEquals(7L, profile.eqpKey());
        assertEquals("SOCKET", profile.commInterfaceType());
    }

    @Test
    @DisplayName("PLC 모델 변경 operation 은 새 모델의 디코드 스펙(groups)을 운반한다")
    void modelUpdateProfileCarriesTheNewModelDecodeSpec() {
        final ModelDetailQueryPort modelPort = mock(ModelDetailQueryPort.class);
        when(modelPort.findPlcGroupsByModelVersionKey(NEW_MODEL_VERSION_KEY)).thenReturn(List.of(sampleGroup()));
        when(modelPort.findPlcRegistersByModelVersionKey(NEW_MODEL_VERSION_KEY)).thenReturn(List.of(sampleRegister()));

        final UiEquipmentProfileSnapshot profile = factory(plcSnapshot(), modelPort).profileFor(
                new UiOperationManifestView(
                        "EQP_MODEL_UPDATE", EQP_ID, "tester", null, "PLC", true,
                        List.of("equipment:" + EQP_ID),
                        Map.of("equipment:" + EQP_ID, "{\"eqpId\":\"" + EQP_ID + "\"}"),
                        Map.of("equipment:" + EQP_ID, 0L),
                        null, null, Map.of(),
                        UiOperationRequest.DB_COMMAND_UPDATE,
                        objectMapper.writeValueAsString(
                                updateCommand("10.0.0.1", 502, "ACTIVE", NEW_MODEL_VERSION_KEY))));

        assertNotNull(profile);
        assertEquals(NEW_MODEL_VERSION_KEY, profile.modelKey());
        assertNotNull(profile.plcSettings());
        assertFalse(profile.plcSettings().groups().isEmpty(),
                "새 모델의 디코드 스펙을 싣지 않으면 Gateway 는 옛 스펙으로 폴링을 계속합니다.");
        assertEquals("EQP_STATUS_REPORT", profile.plcSettings().groups().get(0).messageName());
    }

    @Test
    @DisplayName("§16.1 — 생성 operation 의 profile 은 임시 행의 양수 eqpKey 를 담는다")
    void createProfileCarriesTheStagedRowIdentity() {
        final UiOperationManifestView manifest = new UiOperationManifestView(
                "EQP_CREATE", EQP_ID, "tester", null, "SOCKET", true,
                List.of("equipment:" + EQP_ID),
                Map.of("equipment:" + EQP_ID, "{\"eqpId\":\"" + EQP_ID + "\"}"),
                Map.of("equipment:" + EQP_ID, 0L),
                null, null,
                Map.of("equipment:" + EQP_ID, "{\"eqpId\":\"" + EQP_ID + "\",\"deleted\":true}"),
                UiOperationRequest.DB_COMMAND_CREATE,
                objectMapper.writeValueAsString(
                        updateCommand("10.0.0.1", 6000, "PASSIVE", MODEL_VERSION_KEY)));

        final UiEquipmentProfileSnapshot profile =
                factory(socketSnapshot(), mock(ModelDetailQueryPort.class)).profileFor(manifest);

        assertNotNull(profile, "Gateway 는 profile 이 없으면 컨텍스트를 만들지 못합니다.");
        // Gateway 는 eqpKey 를 양수로 요구한다 — 그 출처가 PREPARE 전에 만들어 둔 임시 행이다.
        assertEquals(7L, profile.eqpKey());
        assertEquals(EQP_ID, profile.eqpId());
    }

    @Test
    @DisplayName("Gateway 참여가 필요 없는 operation 은 profile 을 만들지 않는다")
    void nonParticipatingOperationHasNoProfile() {
        final UiOperationManifestView manifest = new UiOperationManifestView(
                "EQP_UPDATE", EQP_ID, "tester", null, "SOCKET", false,
                List.of("equipment:" + EQP_ID),
                Map.of("equipment:" + EQP_ID, "{\"eqpId\":\"" + EQP_ID + "\"}"),
                Map.of("equipment:" + EQP_ID, 0L),
                null, null, Map.of(),
                UiOperationRequest.DB_COMMAND_UPDATE,
                objectMapper.writeValueAsString(updateCommand("10.0.0.9", 6100, "ACTIVE", MODEL_VERSION_KEY)));

        assertNull(factory(socketSnapshot(), mock(ModelDetailQueryPort.class)).profileFor(manifest));
    }

    @Test
    @DisplayName("삭제 operation 은 profile 을 만들지 않는다 — Gateway 삭제 경로는 payload 를 요구하지 않는다")
    void deleteOperationHasNoProfile() {
        final UiOperationManifestView manifest = new UiOperationManifestView(
                "EQP_DELETE", EQP_ID, "SYSTEM", null, "SOCKET", true,
                List.of("equipment:" + EQP_ID),
                Map.of("equipment:" + EQP_ID, "{\"eqpId\":\"" + EQP_ID + "\",\"deleted\":true}"),
                Map.of("equipment:" + EQP_ID, 0L),
                null, null, Map.of(),
                UiOperationRequest.DB_COMMAND_DELETE, "{}");

        assertNull(factory(socketSnapshot(), mock(ModelDetailQueryPort.class)).profileFor(manifest));
    }

    @Test
    @DisplayName("수정 대상 행이 사라졌으면 profile 을 지어내지 않는다")
    void missingRowProducesNoProfile() {
        final EqpManageQueryPort emptyPort = mock(EqpManageQueryPort.class);
        when(emptyPort.findManageSnapshotByEqpId(anyString())).thenReturn(Optional.empty());

        final UiCoordinatedGatewayProfileFactory factory = new UiCoordinatedGatewayProfileFactory(
                emptyPort,
                localMutationCoordinator(),
                new DeployCommandAssembler(commandSupport(mock(ModelDetailQueryPort.class))),
                mock(DeployStorePort.class),
                objectMapper);

        assertNull(factory.profileFor(
                updateManifest(updateCommand("10.0.0.9", 6100, "ACTIVE", MODEL_VERSION_KEY))));
    }

    @Test
    @DisplayName("Gateway profile 은 socket 설정도 목표 값으로 담는다")
    void socketSettingsFollowTheTargetCommand() {
        final EqpManagementCommand.Update command = new EqpManagementCommand.Update(
                "tester", 1L, "ACTIVE", false, 0, "10.0.0.1", 6000,
                MODEL_VERSION_KEY, "V1", null, null, null, null,
                new EqpManagementCommand.SocketSettings(
                        "LINE_DELIMITED", "UTF-8", true, 5, 3000, 3000, 4096, true),
                null);

        final UiEquipmentProfileSnapshot profile =
                factory(socketSnapshot(), mock(ModelDetailQueryPort.class)).profileFor(updateManifest(command));

        assertNotNull(profile.socketSettings());
        assertEquals("LINE_DELIMITED", profile.socketSettings().socketProtocolType());
        assertEquals("ACTIVE", profile.socketSettings().connectionMode());
        assertTrue(profile.socketSettings().keepAliveEnabled());
    }

    // ------------------------------------------------------------------ fixture

    private UiCoordinatedGatewayProfileFactory factory(
            final EqpManagementSnapshot before,
            final ModelDetailQueryPort modelDetailQueryPort
    ) {
        final EqpManageQueryPort queryPort = mock(EqpManageQueryPort.class);
        when(queryPort.findManageSnapshotByEqpId(anyString())).thenReturn(Optional.of(before));
        return new UiCoordinatedGatewayProfileFactory(
                queryPort,
                localMutationCoordinator(),
                new DeployCommandAssembler(commandSupport(modelDetailQueryPort)),
                mock(DeployStorePort.class),
                objectMapper);
    }

    private EqpDirectScenarioLocalMutationCoordinator localMutationCoordinator() {
        return new EqpDirectScenarioLocalMutationCoordinator(
                mock(EqpPersistenceService.class),
                mock(EqpCrudPort.class),
                mock(ModelDetailQueryPort.class),
                objectMapper);
    }

    private DeployServiceCommandSupport commandSupport(final ModelDetailQueryPort modelDetailQueryPort) {
        return new DeployServiceCommandSupport(
                mock(DeployStorePort.class),
                mock(EqpCrudPort.class),
                mock(EqpQueryPort.class),
                modelDetailQueryPort,
                mock(UiGatewayEventPublishPort.class),
                mock(UiBusinessEventPublishPort.class),
                objectMapper);
    }

    private UiOperationManifestView updateManifest(final EqpManagementCommand.Update command) {
        return new UiOperationManifestView(
                "EQP_UPDATE", EQP_ID, "tester", null, "SOCKET", true,
                List.of("equipment:" + EQP_ID),
                Map.of("equipment:" + EQP_ID, "{\"eqpId\":\"" + EQP_ID + "\"}"),
                Map.of("equipment:" + EQP_ID, 0L),
                null, null, Map.of(),
                UiOperationRequest.DB_COMMAND_UPDATE,
                objectMapper.writeValueAsString(command));
    }

    private static EqpManagementCommand.Update updateCommand(
            final String eqpIp,
            final Integer eqpPort,
            final String commMode,
            final long modelVersionKey
    ) {
        return new EqpManagementCommand.Update(
                "tester", 1L, commMode, false, 0, eqpIp, eqpPort,
                modelVersionKey, "V1", null, null, null, null, null, null);
    }

    private static EqpManagementSnapshot socketSnapshot() {
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        final UiEqp eqp = new UiEqp(
                7L, EQP_ID, 1L, UiProtocolType.SOCKET, "PASSIVE", false, 0,
                "10.0.0.1", 6000, MODEL_VERSION_KEY, "V1", null, null, true, now, now, "tester", "tester");
        final UiEqpSocket socket = new UiEqpSocket(
                7L, "FIXED_LENGTH", "UTF-8", false, 0, 3000, 3000, 4096, false, now, now);
        return new EqpManagementSnapshot(
                eqp, null, null, socket, null, null, null, null,
                List.of(), List.of(), List.of(), null, null);
    }

    private static EqpManagementSnapshot plcSnapshot() {
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        final UiEqp eqp = new UiEqp(
                7L, EQP_ID, 1L, UiProtocolType.PLC, "ACTIVE", false, 0,
                "10.0.0.1", 502, MODEL_VERSION_KEY, "V1", null, null, true, now, now, "tester", "tester");
        final UiEqpPlc plc = new UiEqpPlc(
                7L, "MODBUS_TCP", "TCP", 1, 1000, 3, List.of("EQP_STATUS_REPORT"), 0L, now, now);
        return new EqpManagementSnapshot(
                eqp, null, null, null, plc, null, null, null,
                List.of(), List.of(), List.of(), null, null);
    }

    private static UiModelPlcGroup sampleGroup() {
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new UiModelPlcGroup(
                10L, NEW_MODEL_VERSION_KEY, "EQP_STATUS_REPORT", "HOLDING_REGISTER", 100, 4, "0x", 1000, "상태", now);
    }

    private static UiModelPlcRegister sampleRegister() {
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new UiModelPlcRegister(
                20L, 10L, "EQP_STATUS_REPORT", 0, "TEMP", "UINT16",
                null, null, null, "BIG_ENDIAN", "BIG_ENDIAN", 0.1, null, null, "온도", now);
    }
}
