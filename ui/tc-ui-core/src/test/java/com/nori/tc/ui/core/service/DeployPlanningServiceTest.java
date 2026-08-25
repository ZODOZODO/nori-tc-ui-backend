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
import com.nori.tc.ui.core.deploy.DeployTargetApp;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
                objectMapper,
                com.nori.tc.ui.core.coordination.UiScenarioCatalogGuard.disabled()
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
    void planRegisterModelDeployForPlcGeneratesGatewayStep() {
        // AC7: PLC 설비의 MODEL_CHANGED register deploy 는 targetApp: GATEWAY step(UPSERT_GATEWAY_CONTEXT)을 생성한다.
        final DeployScenarioCatalogService catalogService = new DeployScenarioCatalogService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                new InMemoryCatalogMetaPort(),
                objectMapper,
                com.nori.tc.ui.core.coordination.UiScenarioCatalogGuard.disabled()
        );
        catalogService.initializeCatalog();
        final DeployPlanningService plcPlanningService = new DeployPlanningService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                eqpId -> Optional.of(plcCurrentState(eqpId)),
                catalogService,
                objectMapper
        );

        final DeployPlanCommand command = new DeployPlanCommand(
                "Register Model Deploy (PLC)",
                UiCommandEventType.EQP_REGISTER_DEPLOY,
                DeployExecutionPolicy.BEST_EFFORT,
                new DeployPlanCommand.CommonRequest("PLC", registerModelUiMessage(), "REGISTER_MODEL_ONLY"),
                List.of(new DeployPlanCommand.TargetRequest("PLC_001", objectMapper.createObjectNode()))
        );

        final var target = plcPlanningService.plan(command).targets().getFirst();

        assertTrue(target.deltaKinds().contains(DeployDeltaKind.PLC_MODEL_CHANGED),
                "AC7: PLC 모델 변경은 PLC_MODEL_CHANGED delta 를 생성한다");
        final var gatewayStep = target.resolvedSteps().stream()
                .filter(step -> "UPSERT_GATEWAY_CONTEXT".equals(step.code()))
                .findFirst()
                .orElse(null);
        assertNotNull(gatewayStep, "AC7: PLC register-model deploy 는 GATEWAY step 을 생성한다");
        assertEquals(DeployTargetApp.GATEWAY, gatewayStep.targetApp());
    }

    @Test
    void planRegisterModelDeployForSocketDoesNotGenerateGatewayStep() {
        // R6: SECS/SOCKET 설비의 모델 변경은 PLC_MODEL_CHANGED 미생성 → GATEWAY step 미생성(무회귀).
        final DeployPlanCommand command = new DeployPlanCommand(
                "Register Model Deploy (SOCKET)",
                UiCommandEventType.EQP_REGISTER_DEPLOY,
                DeployExecutionPolicy.BEST_EFFORT,
                new DeployPlanCommand.CommonRequest("SOCKET", registerModelUiMessage(), "REGISTER_MODEL_ONLY"),
                List.of(new DeployPlanCommand.TargetRequest("PHOTO_001", objectMapper.createObjectNode()))
        );

        final var target = deployPlanningService.plan(command).targets().getFirst();

        assertFalse(target.deltaKinds().contains(DeployDeltaKind.PLC_MODEL_CHANGED),
                "R6: SOCKET 은 PLC_MODEL_CHANGED 를 생성하지 않는다");
        assertFalse(
                target.resolvedSteps().stream().anyMatch(step -> "UPSERT_GATEWAY_CONTEXT".equals(step.code())),
                "R6: SOCKET register-model deploy 는 GATEWAY step 을 생성하지 않는다"
        );
    }

    @Test
    void planUpdateMergesPlcSettingsIntoEquipmentProfile() {
        // L1337 deploy(update) 경로 — 빌더 ⓑ DeployPlanningService.mergeEquipmentProfile 가 base profile 의
        // plcSettings 를 mergedUiMessage.equipmentProfile 로 운반하는지 검증(누락 시 PLC 설비가 폴링 스펙 없이 기동).
        final DeployScenarioCatalogService catalogService = new DeployScenarioCatalogService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                new InMemoryCatalogMetaPort(),
                objectMapper,
                com.nori.tc.ui.core.coordination.UiScenarioCatalogGuard.disabled()
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

    // === Phase 0 (deploy-bulk-register-perf) — 다중 target 동등성 baseline ===
    // 배치화(Phase 2/3) 전후로 이 테스트들이 무수정 GREEN 이면 서비스 계층 loop→map-lookup 동등성이 보존된 것이다.
    // 포트는 eqpId→상태 map 기반 stub 이므로 default 배치 메서드는 단건 lambda 를 loop 한다(어댑터 SQL 동등성은 별도 검증).

    @Test
    void previewRegisterScenarioAggregatesMixedTargetsIntoUnionAndRecommendation() {
        final Map<String, DeployCurrentState> states = new LinkedHashMap<>();
        // EQP_A: 모델·jar·param 모두 desired 와 동일 → NO_CHANGE → removed
        states.put("EQP_A", socketCurrentState("EQP_A", 2002L, 9002L, "PARAM_V1", null));
        // EQP_B: 모델만 상이(1001 vs 2002) → MODEL_CHANGED
        states.put("EQP_B", socketCurrentState("EQP_B", 1001L, 9002L, "PARAM_V1", null));
        // EQP_C: business jar 만 상이(8001 vs 9002) → BUSINESS_JAR_CHANGED
        states.put("EQP_C", socketCurrentState("EQP_C", 2002L, 8001L, "PARAM_V1", null));
        // EQP_D: param 만 상이(V1 vs override V2) → EQP_PARAM_CHANGED
        states.put("EQP_D", socketCurrentState("EQP_D", 2002L, 9002L, "PARAM_V1", null));

        final ObjectNode commonUiMessage = objectMapper.createObjectNode();
        commonUiMessage.putObject("modelInfo").put("modelVersionKey", 2002L);
        commonUiMessage.putObject("businessJar").put("jarRef", 9002L);

        final var preview = serviceWithStates(states).previewRegisterScenarioSelection(
                new DeployRegisterScenarioPreviewCommand(
                        "SOCKET",
                        commonUiMessage,
                        List.of(
                                new DeployRegisterScenarioPreviewCommand.TargetRequest("EQP_A", registerEqpParamUiMessage("PARAM_V1")),
                                new DeployRegisterScenarioPreviewCommand.TargetRequest("EQP_B", registerEqpParamUiMessage("PARAM_V1")),
                                new DeployRegisterScenarioPreviewCommand.TargetRequest("EQP_C", registerEqpParamUiMessage("PARAM_V1")),
                                new DeployRegisterScenarioPreviewCommand.TargetRequest("EQP_D", registerEqpParamUiMessage("PARAM_V2"))
                        )
                )
        );

        assertEquals(List.of("EQP_A"), preview.removedEqpIds());
        assertEquals(List.of("EQP_B", "EQP_C", "EQP_D"), preview.remainingEqpIds());
        assertEquals(
                Set.of(
                        DeployDeltaKind.MODEL_CHANGED,
                        DeployDeltaKind.BUSINESS_JAR_CHANGED,
                        DeployDeltaKind.EQP_PARAM_CHANGED
                ),
                preview.unionChangeKinds()
        );
        assertEquals("REGISTER_MODEL_BUSINESS_JAR_EQP_PARAM", preview.recommendedScenarioCode());
    }

    @Test
    void planRegisterDeployComputesPerTargetDeltaAndStepsForMixedInterfaces() {
        final Map<String, DeployCurrentState> states = new LinkedHashMap<>();
        // PLC 설비: 모델 변경 → MODEL_CHANGED + PLC_MODEL_CHANGED
        states.put("PLC_1", plcCurrentState("PLC_1"));
        // SOCKET 설비: 모델만 변경 → MODEL_CHANGED
        states.put("SOCKET_1", socketCurrentState("SOCKET_1", 1001L, 4001L, "EQP_PARAM_V1", "REMOTE"));

        final ObjectNode commonUiMessage = objectMapper.createObjectNode();
        commonUiMessage.putObject("modelInfo").put("modelVersionKey", 2002L);
        commonUiMessage.putObject("businessJar").put("jarRef", 4001L);

        final DeployPlanCommand command = new DeployPlanCommand(
                "Register Deploy Mixed",
                UiCommandEventType.EQP_REGISTER_DEPLOY,
                DeployExecutionPolicy.BEST_EFFORT,
                new DeployPlanCommand.CommonRequest("SOCKET", commonUiMessage, "REGISTER_MODEL_BUSINESS_JAR_EQP_PARAM"),
                List.of(
                        new DeployPlanCommand.TargetRequest("PLC_1", objectMapper.createObjectNode()),
                        new DeployPlanCommand.TargetRequest("SOCKET_1", objectMapper.createObjectNode())
                )
        );

        final DeployPlanningResult result = serviceWithStates(states).plan(command);
        final var plcTarget = result.targets().get(0);
        final var socketTarget = result.targets().get(1);

        assertEquals(
                Set.of(DeployDeltaKind.MODEL_CHANGED, DeployDeltaKind.PLC_MODEL_CHANGED),
                plcTarget.deltaKinds()
        );
        assertEquals("REGISTER_MODEL_BUSINESS_JAR_EQP_PARAM", plcTarget.resolvedScenarioCode());
        assertIterableEquals(
                List.of("UPSERT_GATEWAY_CONTEXT", "UPDATE_MODEL_BINDING"),
                plcTarget.resolvedSteps().stream().map(step -> step.code()).toList()
        );

        assertEquals(Set.of(DeployDeltaKind.MODEL_CHANGED), socketTarget.deltaKinds());
        assertEquals("REGISTER_MODEL_BUSINESS_JAR_EQP_PARAM", socketTarget.resolvedScenarioCode());
        assertIterableEquals(
                List.of("UPDATE_MODEL_BINDING"),
                socketTarget.resolvedSteps().stream().map(step -> step.code()).toList()
        );
    }

    @Test
    void planUpdateFiltersReconnectStepByPerTargetControlState() {
        final Map<String, DeployCurrentState> states = new LinkedHashMap<>();
        // controlState REMOTE → RECONNECT_GATEWAY 허용
        states.put("REMOTE_1", socketCurrentState("REMOTE_1", 1001L, 4001L, "EQP_PARAM_V1", "REMOTE"));
        // controlState OFFLINE → RECONNECT_GATEWAY 차단(step 필터)
        states.put("OFFLINE_1", socketCurrentState("OFFLINE_1", 1001L, 4001L, "EQP_PARAM_V1", "OFFLINE"));

        final DeployPlanCommand command = new DeployPlanCommand(
                "Update Reconnect Mixed",
                UiCommandEventType.EQP_UPDATE,
                DeployExecutionPolicy.BEST_EFFORT,
                new DeployPlanCommand.CommonRequest("SOCKET", updateUiMessage(), null),
                List.of(
                        new DeployPlanCommand.TargetRequest("REMOTE_1", objectMapper.createObjectNode()),
                        new DeployPlanCommand.TargetRequest("OFFLINE_1", objectMapper.createObjectNode())
                )
        );

        final DeployPlanningResult result = serviceWithStates(states).plan(command);
        final var remoteTarget = result.targets().get(0);
        final var offlineTarget = result.targets().get(1);

        assertEquals(Set.of(DeployDeltaKind.CONNECTION_ENDPOINT_CHANGED), remoteTarget.deltaKinds());
        assertIterableEquals(
                List.of("UPSERT_GATEWAY_CONTEXT", "RECONNECT_GATEWAY"),
                remoteTarget.resolvedSteps().stream().map(step -> step.code()).toList()
        );
        assertEquals(Set.of(DeployDeltaKind.CONNECTION_ENDPOINT_CHANGED), offlineTarget.deltaKinds());
        assertIterableEquals(
                List.of("UPSERT_GATEWAY_CONTEXT"),
                offlineTarget.resolvedSteps().stream().map(step -> step.code()).toList()
        );
    }

    private DeployPlanningService serviceWithStates(final Map<String, DeployCurrentState> states) {
        final DeployScenarioCatalogService catalogService = new DeployScenarioCatalogService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                new InMemoryCatalogMetaPort(),
                objectMapper,
                com.nori.tc.ui.core.coordination.UiScenarioCatalogGuard.disabled()
        );
        catalogService.initializeCatalog();
        return new DeployPlanningService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                eqpId -> Optional.ofNullable(states.get(eqpId)),
                catalogService,
                objectMapper
        );
    }

    private DeployCurrentState socketCurrentState(
            final String eqpId,
            final long modelVersionKey,
            final Long businessJarRef,
            final String paramVersion,
            final String controlState
    ) {
        return new DeployCurrentState(
                1L, eqpId, "SOCKET", "ACTIVE", "10.10.10.1", 6000,
                modelVersionKey, paramVersion, 3001L, businessJarRef, "TEST01", null, 0,
                controlState == null ? null : socketEquipmentProfile(eqpId, controlState)
        );
    }

    private UiEquipmentProfileSnapshot socketEquipmentProfile(final String eqpId, final String controlState) {
        return new UiEquipmentProfileSnapshot(
                1L, eqpId, "SOCKET", "TEST01", null, "10.10.10.1", 6000, 1001L, 3001L, 4001L, "ACTIVE", 0, true,
                null, null,
                new UiEquipmentProfileSnapshot.CurrentStateSnapshot(controlState, null, null, null, null, null),
                null, null, null, null, "ENDED", null
        );
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
                        "MODBUS_TCP", "TCP", 3, 3000, 5, List.of("EQP_STATUS_REPORT"), List.of())
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
