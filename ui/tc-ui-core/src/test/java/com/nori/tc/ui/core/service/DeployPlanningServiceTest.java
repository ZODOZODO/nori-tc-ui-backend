package com.nori.tc.ui.core.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.nori.tc.ui.core.deploy.DeployCurrentState;
import com.nori.tc.ui.core.deploy.DeployDeltaKind;
import com.nori.tc.ui.core.deploy.DeployExecutionPolicy;
import com.nori.tc.ui.core.deploy.DeployPlanCommand;
import com.nori.tc.ui.core.deploy.DeployPlanningResult;
import com.nori.tc.ui.core.deploy.DeployRegisterScenarioPreviewCommand;
import com.nori.tc.ui.core.deploy.DeployScenarioCatalogMeta;
import com.nori.tc.ui.core.model.UiEquipmentProfileSnapshot;
import com.nori.tc.ui.core.port.db.DeployScenarioCatalogMetaPort;
import com.nori.tc.ui.core.properties.UiDeployProperties;
import com.nori.tc.ui.core.model.UiCommandEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeployPlanningServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private DeployPlanningService deployPlanningService;

    @BeforeEach
    void setUp() {
        final DeployScenarioCatalogService catalogService = new DeployScenarioCatalogService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                new InMemoryCatalogMetaPort(),
                objectMapper
        );
        catalogService.initializeCatalog();

        deployPlanningService = new DeployPlanningService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                eqpId -> Optional.of(new DeployCurrentState(
                        1L,
                        eqpId,
                        "SOCKET",
                        "ACTIVE",
                        "10.10.10.1",
                        6000,
                        1001L,
                        "EQP_PARAM_V1",
                        3001L,
                        4001L,
                        "TEST01",
                        null,
                        0,
                        remoteEquipmentProfile(eqpId)
                )),
                catalogService,
                objectMapper
        );
    }

    @Test
    void planGeneratesStableResolvedStepsForSameInput() {
        final DeployPlanCommand command = new DeployPlanCommand(
                "PHOTO Update",
                UiCommandEventType.EQP_UPDATE,
                DeployExecutionPolicy.BEST_EFFORT,
                new DeployPlanCommand.CommonRequest("SOCKET", updateUiMessage(), null),
                List.of(new DeployPlanCommand.TargetRequest("PHOTO_001", objectMapper.createObjectNode()))
        );

        final DeployPlanningResult first = deployPlanningService.plan(command);
        final DeployPlanningResult second = deployPlanningService.plan(command);

        final var firstTarget = first.targets().getFirst();
        final var secondTarget = second.targets().getFirst();

        assertEquals(Set.of(DeployDeltaKind.CONNECTION_ENDPOINT_CHANGED), firstTarget.deltaKinds());
        assertEquals("EQP_UPDATE_GENERIC", firstTarget.resolvedScenarioCode());
        assertIterableEquals(
                List.of("UPSERT_GATEWAY_CONTEXT", "RECONNECT_GATEWAY"),
                firstTarget.resolvedSteps().stream().map(step -> step.code()).toList()
        );
        assertEquals(firstTarget.deltaJson(), secondTarget.deltaJson());
        assertEquals(firstTarget.resolvedStepPlanJson(), secondTarget.resolvedStepPlanJson());
    }

    @Test
    void planMarksTargetAsNoChangeWhenDesiredStateMatchesCurrentState() {
        final DeployPlanCommand command = new DeployPlanCommand(
                "PHOTO Update No Change",
                UiCommandEventType.EQP_UPDATE,
                DeployExecutionPolicy.BEST_EFFORT,
                new DeployPlanCommand.CommonRequest("SOCKET", noChangeUiMessage(), null),
                List.of(new DeployPlanCommand.TargetRequest("PHOTO_001", objectMapper.createObjectNode()))
        );

        final DeployPlanningResult result = deployPlanningService.plan(command);
        final var target = result.targets().getFirst();

        assertTrue(target.noChange());
        assertEquals(Set.of(DeployDeltaKind.NO_CHANGE), target.deltaKinds());
        assertTrue(target.resolvedSteps().isEmpty());
        assertEquals("EQP_UPDATE_GENERIC", target.resolvedScenarioCode());
    }

    @Test
    void previewRegisterScenarioRemovesNoChangeTargetAndRecommendsExactScenario() {
        final var preview = deployPlanningService.previewRegisterScenarioSelection(
                new DeployRegisterScenarioPreviewCommand(
                        "SOCKET",
                        objectMapper.createObjectNode(),
                        List.of(
                                new DeployRegisterScenarioPreviewCommand.TargetRequest(
                                        "PHOTO_001",
                                        registerEqpParamUiMessage("EQP_PARAM_V2")
                                ),
                                new DeployRegisterScenarioPreviewCommand.TargetRequest(
                                        "PHOTO_002",
                                        objectMapper.createObjectNode()
                                )
                        )
                )
        );

        assertEquals(List.of("PHOTO_002"), preview.removedEqpIds());
        assertEquals(List.of("PHOTO_001"), preview.remainingEqpIds());
        assertEquals(Set.of(DeployDeltaKind.EQP_PARAM_CHANGED), preview.unionChangeKinds());
        assertEquals("REGISTER_EQP_PARAM_ONLY", preview.recommendedScenarioCode());
        assertTrue(preview.scenarios().stream().anyMatch(candidate ->
                candidate.code().equals("REGISTER_EQP_PARAM_ONLY") && candidate.recommended()));
    }

    @Test
    void planRegisterDeployUsesSelectedScenarioAndFiltersTargetStepsByDelta() {
        final DeployPlanCommand command = new DeployPlanCommand(
                "Register Deploy",
                UiCommandEventType.EQP_REGISTER_DEPLOY,
                DeployExecutionPolicy.BEST_EFFORT,
                new DeployPlanCommand.CommonRequest(
                        "SOCKET",
                        registerModelUiMessage(),
                        "REGISTER_MODEL_EQP_PARAM"
                ),
                List.of(new DeployPlanCommand.TargetRequest("PHOTO_001", objectMapper.createObjectNode()))
        );

        final DeployPlanningResult result = deployPlanningService.plan(command);
        final var target = result.targets().getFirst();

        assertEquals("REGISTER_MODEL_EQP_PARAM", target.resolvedScenarioCode());
        assertEquals(Set.of(DeployDeltaKind.MODEL_CHANGED), target.deltaKinds());
        assertIterableEquals(
                List.of("UPDATE_MODEL_BINDING"),
                target.resolvedSteps().stream().map(step -> step.code()).toList()
        );
    }

    @Test
    void planRegisterBusinessJarDeployUsesSinglePluginReloadStep() {
        final DeployPlanCommand command = new DeployPlanCommand(
                "Register Business Jar Deploy",
                UiCommandEventType.EQP_REGISTER_DEPLOY,
                DeployExecutionPolicy.BEST_EFFORT,
                new DeployPlanCommand.CommonRequest(
                        "SOCKET",
                        registerBusinessJarUiMessage(),
                        "REGISTER_BUSINESS_JAR_ONLY"
                ),
                List.of(new DeployPlanCommand.TargetRequest("PHOTO_001", objectMapper.createObjectNode()))
        );

        final DeployPlanningResult result = deployPlanningService.plan(command);
        final var target = result.targets().getFirst();

        assertEquals("REGISTER_BUSINESS_JAR_ONLY", target.resolvedScenarioCode());
        assertEquals(Set.of(DeployDeltaKind.BUSINESS_JAR_CHANGED), target.deltaKinds());
        assertIterableEquals(
                List.of("RELOAD_BUSINESS_PLUGIN"),
                target.resolvedSteps().stream().map(step -> step.code()).toList()
        );
    }

    @Test
    void planRegisterModelBusinessJarDeployUsesSingleModelBindingStep() {
        final DeployPlanCommand command = new DeployPlanCommand(
                "Register Model Business Jar Deploy",
                UiCommandEventType.EQP_REGISTER_DEPLOY,
                DeployExecutionPolicy.BEST_EFFORT,
                new DeployPlanCommand.CommonRequest(
                        "SOCKET",
                        registerModelBusinessJarUiMessage(),
                        "REGISTER_MODEL_BUSINESS_JAR"
                ),
                List.of(new DeployPlanCommand.TargetRequest("PHOTO_001", objectMapper.createObjectNode()))
        );

        final DeployPlanningResult result = deployPlanningService.plan(command);
        final var target = result.targets().getFirst();

        assertEquals("REGISTER_MODEL_BUSINESS_JAR", target.resolvedScenarioCode());
        assertEquals(Set.of(DeployDeltaKind.MODEL_CHANGED, DeployDeltaKind.BUSINESS_JAR_CHANGED), target.deltaKinds());
        assertIterableEquals(
                List.of("UPDATE_MODEL_BINDING"),
                target.resolvedSteps().stream().map(step -> step.code()).toList()
        );
    }

    @Test
    void planRegisterModelBusinessJarEqpParamDeployUsesModelBindingAndEqpParamSteps() {
        final DeployPlanCommand command = new DeployPlanCommand(
                "Register Model Business Jar Eqp Param Deploy",
                UiCommandEventType.EQP_REGISTER_DEPLOY,
                DeployExecutionPolicy.BEST_EFFORT,
                new DeployPlanCommand.CommonRequest(
                        "SOCKET",
                        registerModelBusinessJarEqpParamUiMessage("EQP_PARAM_V2"),
                        "REGISTER_MODEL_BUSINESS_JAR_EQP_PARAM"
                ),
                List.of(new DeployPlanCommand.TargetRequest("PHOTO_001", objectMapper.createObjectNode()))
        );

        final DeployPlanningResult result = deployPlanningService.plan(command);
        final var target = result.targets().getFirst();

        assertEquals("REGISTER_MODEL_BUSINESS_JAR_EQP_PARAM", target.resolvedScenarioCode());
        assertEquals(
                Set.of(DeployDeltaKind.MODEL_CHANGED, DeployDeltaKind.BUSINESS_JAR_CHANGED, DeployDeltaKind.EQP_PARAM_CHANGED),
                target.deltaKinds()
        );
        assertIterableEquals(
                List.of("UPDATE_MODEL_BINDING", "UPDATE_EQP_PARAM"),
                target.resolvedSteps().stream().map(step -> step.code()).toList()
        );
    }

    @Test
    void planUpdateMergesPlcSettingsIntoEquipmentProfile() {
        // L1337 deploy(update) 경로 — 빌더 ⓑ DeployPlanningService.mergeEquipmentProfile 가 base profile 의
        // plcSettings 를 mergedUiMessage.equipmentProfile 로 운반하는지 검증(누락 시 PLC 설비가 폴링 스펙 없이 기동).
        final DeployScenarioCatalogService catalogService = new DeployScenarioCatalogService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                new InMemoryCatalogMetaPort(),
                objectMapper
        );
        catalogService.initializeCatalog();
        final DeployPlanningService plcPlanningService = new DeployPlanningService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                eqpId -> Optional.of(plcCurrentState(eqpId)),
                catalogService,
                objectMapper
        );

        final DeployPlanCommand command = new DeployPlanCommand(
                "PLC Update",
                UiCommandEventType.EQP_UPDATE,
                DeployExecutionPolicy.BEST_EFFORT,
                new DeployPlanCommand.CommonRequest("PLC", plcUpdateUiMessage(), null),
                List.of(new DeployPlanCommand.TargetRequest("PLC_001", objectMapper.createObjectNode()))
        );

        final var target = plcPlanningService.plan(command).targets().getFirst();
        final var equipmentProfile = target.desiredState().mergedUiMessage().path("equipmentProfile");
        final var plcSettings = equipmentProfile.path("plcSettings");

        assertFalse(plcSettings.isMissingNode() || plcSettings.isNull(), "deploy 경로 equipmentProfile.plcSettings 누락");
        assertEquals("MODBUS_TCP", plcSettings.path("plcProtocol").asString());
        assertEquals("TCP", plcSettings.path("transport").asString());
        assertEquals(3, plcSettings.path("unitId").asInt());
        assertEquals(3000, plcSettings.path("pollTimeoutMs").asInt());
        assertEquals(5, plcSettings.path("reconnectMaxRetries").asInt());
    }

    private DeployCurrentState plcCurrentState(final String eqpId) {
        return new DeployCurrentState(
                1L, eqpId, "PLC", "ACTIVE", "10.30.0.1", 502, 1001L, "EQP_PARAM_V1", 3001L, 4001L,
                null, null, 0, plcEquipmentProfile(eqpId)
        );
    }

    private UiEquipmentProfileSnapshot plcEquipmentProfile(final String eqpId) {
        return new UiEquipmentProfileSnapshot(
                1L, eqpId, "PLC", null, null, "10.30.0.1", 502, 1001L, 3001L, 4001L, "ACTIVE", 0, true,
                null, null,
                new UiEquipmentProfileSnapshot.CurrentStateSnapshot("REMOTE", null, null, null, null, null),
                null, null, null, null, "STARTED",
                new UiEquipmentProfileSnapshot.PlcSettingsSnapshot(
                        "MODBUS_TCP", "TCP", 3, 3000, 5, List.of("EQP_STATUS_REPORT"))
        );
    }

    private ObjectNode plcUpdateUiMessage() {
        final ObjectNode root = objectMapper.createObjectNode();
        root.putObject("gatewayProfile")
                .put("connectionMode", "ACTIVE")
                .put("eqpIp", "10.30.0.1")
                .put("eqpPort", 502);
        return root;
    }

    private ObjectNode updateUiMessage() {
        final ObjectNode root = objectMapper.createObjectNode();
        root.putObject("gatewayProfile")
                .put("connectionMode", "ACTIVE")
                .put("eqpIp", "10.10.10.9")
                .put("eqpPort", 6100)
                .put("socketType", "TEST01");
        return root;
    }

    private ObjectNode noChangeUiMessage() {
        final ObjectNode root = objectMapper.createObjectNode();
        root.putObject("gatewayProfile")
                .put("connectionMode", "ACTIVE")
                .put("eqpIp", "10.10.10.1")
                .put("eqpPort", 6000)
                .put("socketType", "TEST01");
        return root;
    }

    private ObjectNode registerModelUiMessage() {
        final ObjectNode root = objectMapper.createObjectNode();
        root.putObject("modelInfo")
                .put("modelVersionKey", 1002L);
        return root;
    }

    private ObjectNode registerEqpParamUiMessage(final String version) {
        final ObjectNode root = objectMapper.createObjectNode();
        root.putObject("eqpParams")
                .put("version", version);
        return root;
    }

    private ObjectNode registerBusinessJarUiMessage() {
        final ObjectNode root = objectMapper.createObjectNode();
        root.putObject("modelInfo")
                .put("modelVersionKey", 1001L);
        root.putObject("businessJar")
                .put("jarRef", 9002L);
        return root;
    }

    private ObjectNode registerModelBusinessJarUiMessage() {
        final ObjectNode root = objectMapper.createObjectNode();
        root.putObject("modelInfo")
                .put("modelVersionKey", 1002L);
        root.putObject("businessJar")
                .put("jarRef", 9002L);
        return root;
    }

    private ObjectNode registerModelBusinessJarEqpParamUiMessage(final String version) {
        final ObjectNode root = registerModelBusinessJarUiMessage();
        root.putObject("eqpParams")
                .put("version", version);
        return root;
    }

    private UiEquipmentProfileSnapshot remoteEquipmentProfile(final String eqpId) {
        // RECONNECT_GATEWAY step 은 controlState ∈ {REMOTE, LOCAL} 일 때만 통과한다
        // (DeployPlanningService#isReconnectStepAllowed). 본 fixture 는 reconnect 통과 경로를 검증한다.
        return new UiEquipmentProfileSnapshot(
                1L,
                eqpId,
                "SOCKET",
                "TEST01",
                null,
                "10.10.10.1",
                6000,
                1001L,
                3001L,
                4001L,
                "ACTIVE",
                0,
                true,
                null,
                null,
                new UiEquipmentProfileSnapshot.CurrentStateSnapshot(
                        "REMOTE",
                        null,
                        null,
                        null,
                        null,
                        null
                ),
                null,
                null,
                null,
                null,
                "ENDED",
                null
        );
    }

    private UiDeployProperties deployProperties(final Path scenarioDirectory) {
        return new UiDeployProperties(
                scenarioDirectory.toString(),
                100,
                10,
                20,
                20,
                1000L,
                30000L,
                86400L,
                86400L,
                3900L,
                4200L,
                30,
                90
        );
    }

    private static final class InMemoryCatalogMetaPort implements DeployScenarioCatalogMetaPort {

        private DeployScenarioCatalogMeta latest;
        private int sequence = 0;

        @Override
        public Optional<DeployScenarioCatalogMeta> findLatest() {
            return Optional.ofNullable(latest);
        }

        @Override
        public DeployScenarioCatalogMeta append(
                final String checksum,
                final int scenarioCount,
                final String actor,
                final String reloadReason
        ) {
            latest = new DeployScenarioCatalogMeta(
                    ++sequence,
                    checksum,
                    scenarioCount,
                    OffsetDateTime.now(ZoneOffset.UTC),
                    actor,
                    reloadReason
            );
            return latest;
        }
    }
}
