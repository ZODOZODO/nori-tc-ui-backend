package com.nori.tc.ui.core.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.nori.tc.ui.core.deploy.DeployCurrentState;
import com.nori.tc.ui.core.deploy.DeployDeltaKind;
import com.nori.tc.ui.core.deploy.DeployExecutionPolicy;
import com.nori.tc.ui.core.deploy.DeployPlanCommand;
import com.nori.tc.ui.core.deploy.DeployPlanningResult;
import com.nori.tc.ui.core.deploy.DeployScenarioCatalogMeta;
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
                        null
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
                new DeployPlanCommand.CommonRequest("SOCKET", updateUiMessage()),
                List.of(new DeployPlanCommand.TargetRequest("PHOTO_001", objectMapper.createObjectNode()))
        );

        final DeployPlanningResult first = deployPlanningService.plan(command);
        final DeployPlanningResult second = deployPlanningService.plan(command);

        final var firstTarget = first.targets().getFirst();
        final var secondTarget = second.targets().getFirst();

        assertEquals(Set.of(
                DeployDeltaKind.MODEL_CHANGED,
                DeployDeltaKind.EQP_PARAM_CHANGED,
                DeployDeltaKind.CONNECTION_ENDPOINT_CHANGED
        ), firstTarget.deltaKinds());
        assertEquals("EQP_UPDATE_GENERIC", firstTarget.resolvedScenarioCode());
        assertIterableEquals(
                List.of("UPSERT_GATEWAY_CONTEXT", "RELOAD_BUSINESS_MODEL_RUNTIME", "UPDATE_MODEL_BINDING",
                        "UPDATE_EQP_PARAM", "RECONNECT_GATEWAY"),
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
                new DeployPlanCommand.CommonRequest("SOCKET", noChangeUiMessage()),
                List.of(new DeployPlanCommand.TargetRequest("PHOTO_001", objectMapper.createObjectNode()))
        );

        final DeployPlanningResult result = deployPlanningService.plan(command);
        final var target = result.targets().getFirst();

        assertTrue(target.noChange());
        assertEquals(Set.of(DeployDeltaKind.NO_CHANGE), target.deltaKinds());
        assertTrue(target.resolvedSteps().isEmpty());
        assertEquals("EQP_UPDATE_GENERIC", target.resolvedScenarioCode());
    }

    private ObjectNode updateUiMessage() {
        final ObjectNode root = objectMapper.createObjectNode();
        root.putObject("modelInfo")
                .put("modelKey", "PHOTO_MODEL")
                .put("modelVersion", "1.0.2")
                .put("modelVersionKey", 1002L);
        root.putObject("gatewayProfile")
                .put("connectionMode", "ACTIVE")
                .put("eqpIp", "10.10.10.9")
                .put("eqpPort", 6100)
                .put("socketType", "TEST01");
        root.putObject("modelParams")
                .put("version", "MODEL_PARAM_V2");
        root.putObject("eqpParams")
                .put("version", "EQP_PARAM_V2");
        root.putObject("businessJar")
                .put("jarRef", 4001L)
                .put("jarFileName", "business.jar")
                .put("expectedSha256", "abc")
                .put("sourceType", "DB");
        root.putObject("gatewayJar")
                .put("jarRef", 3001L)
                .put("jarFileName", "gateway.jar")
                .put("expectedSha256", "def")
                .put("sourceType", "DB");
        return root;
    }

    private ObjectNode noChangeUiMessage() {
        final ObjectNode root = objectMapper.createObjectNode();
        root.putObject("modelInfo")
                .put("modelKey", "PHOTO_MODEL")
                .put("modelVersion", "1.0.1")
                .put("modelVersionKey", 1001L);
        root.putObject("gatewayProfile")
                .put("connectionMode", "ACTIVE")
                .put("eqpIp", "10.10.10.1")
                .put("eqpPort", 6000)
                .put("socketType", "TEST01");
        root.putObject("modelParams")
                .put("version", "MODEL_PARAM_V1");
        root.putObject("eqpParams")
                .put("version", "EQP_PARAM_V1");
        root.putObject("businessJar")
                .put("jarRef", 4001L)
                .put("jarFileName", "business.jar")
                .put("expectedSha256", "abc")
                .put("sourceType", "DB");
        root.putObject("gatewayJar")
                .put("jarRef", 3001L)
                .put("jarFileName", "gateway.jar")
                .put("expectedSha256", "def")
                .put("sourceType", "DB");
        return root;
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
