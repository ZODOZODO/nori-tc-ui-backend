package com.nori.tc.ui.core.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import com.nori.tc.ui.core.deploy.DeployActiveEqpConflict;
import com.nori.tc.ui.core.deploy.DeployCreateResult;
import com.nori.tc.ui.core.deploy.DeployActionResult;
import com.nori.tc.ui.core.deploy.DeployDeleteResult;
import com.nori.tc.ui.core.deploy.DeployCurrentState;
import com.nori.tc.ui.core.deploy.DeployExecutionPolicy;
import com.nori.tc.ui.core.deploy.DeployExecutionPlan;
import com.nori.tc.ui.core.deploy.DeployExecutionTarget;
import com.nori.tc.ui.core.deploy.DeployHistoryView;
import com.nori.tc.ui.core.deploy.DeployPlanCommand;
import com.nori.tc.ui.core.deploy.DeployPlanningResult;
import com.nori.tc.ui.core.deploy.DeploySnapshotKind;
import com.nori.tc.ui.core.deploy.DeploySnapshotScope;
import com.nori.tc.ui.core.deploy.DeployStoredSnapshot;
import com.nori.tc.ui.core.deploy.DeployScenarioCatalogMeta;
import com.nori.tc.ui.core.deploy.DeployStatus;
import com.nori.tc.ui.core.deploy.DeployStepStatus;
import com.nori.tc.ui.core.deploy.DeploySummarySnapshot;
import com.nori.tc.ui.core.deploy.DeployTargetCounts;
import com.nori.tc.ui.core.deploy.DeployTargetPage;
import com.nori.tc.ui.core.deploy.DeployTargetSnapshot;
import com.nori.tc.ui.core.deploy.DeployTargetStatus;
import com.nori.tc.ui.core.deploy.PersistedDeploy;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.model.UiCommandMessage;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.model.UiEquipmentProfileSnapshot;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.port.db.DeployScenarioCatalogMetaPort;
import com.nori.tc.ui.core.port.db.DeployStorePort;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.core.port.db.ModelDetailQueryPort;
import com.nori.tc.ui.core.port.messaging.UiBusinessEventPublishPort;
import com.nori.tc.ui.core.port.messaging.UiGatewayEventPublishPort;
import com.nori.tc.ui.core.properties.UiDeployProperties;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.exception.UiErrorCode;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.domain.common.model.UiModelStatus;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpParam;
import com.nori.tc.ui.domain.jar.UiBusinessJar;
import com.nori.tc.ui.domain.model.UiModel;
import com.nori.tc.ui.domain.model.UiModelParam;
import com.nori.tc.ui.domain.task.UiTaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeployServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private DeployService deployService;
    private DeployExecutor deployExecutor;

    @AfterEach
    void tearDown() {
        if (deployService != null) {
            deployService.shutdown();
        }
    }

    @Test
    void createDeployKeepsSingleTargetReadyUntilExplicitExecution() throws Exception {
        final DeployTraceRegistry traceRegistry = new DeployTraceRegistry(new InMemoryDeployTraceRedisPort());
        final InMemoryDeployStorePort storePort = new InMemoryDeployStorePort();
        deployService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService(tempDir.resolve("missing-scenarios")),
                storePort,
                new InMemoryEqpCrudPort(),
                passGatewayPublisher(traceRegistry),
                UiBusinessEventPublishPort.noop(),
                traceRegistry
        );

        final DeployCreateResult result = deployService.createDeploy(sendMessageCommand(), "tester");
        assertEquals(DeployStatus.READY, result.deployStatus());
        assertEquals("DEPLOY_CREATED", result.resultCode());
        assertEquals("Deploy가 생성되었습니다.", result.resultMessage().title());
        assertEquals("Deploy 탭에서 실행해 주세요.", result.resultMessage().message());

        final var summary = deployService.getDeploySummary(result.deployKey());
        assertEquals(DeployStatus.READY, summary.deployStatus());
        assertFalse(summary.terminal());
        assertNull(summary.startedAt());
        assertNull(summary.finishedAt());
        assertEquals(1, summary.targetSummary().pending());

        final DeployTargetPage targetPage = deployService.getDeployTargets(result.deployKey(), 0, 20, null, null);
        assertEquals(1, targetPage.targets().size());
        assertEquals(DeployTargetStatus.PENDING, targetPage.targets().getFirst().targetStatus());
        assertFalse(targetPage.targets().getFirst().rollbackRequired());
    }

    @Test
    void createDeployMarksTargetFailedWhenGatewayReplyFails() throws Exception {
        final DeployTraceRegistry traceRegistry = new DeployTraceRegistry(new InMemoryDeployTraceRedisPort());
        final InMemoryDeployStorePort storePort = new InMemoryDeployStorePort();
        deployService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService(tempDir.resolve("missing-scenarios")),
                storePort,
                new InMemoryEqpCrudPort(),
                failGatewayPublisher(traceRegistry),
                UiBusinessEventPublishPort.noop(),
                traceRegistry
        );

        final DeployCreateResult result = deployService.createDeploy(sendMessageCommand(), "tester");
        executeReadyDeploy(storePort, result.deployKey(), "tester");
        final var summary = awaitTerminalSummary(result.deployKey());

        assertEquals(DeployStatus.FAILED, summary.deployStatus());
        assertEquals("DEPLOY_FAILED", summary.resultCode());
        assertEquals(1, summary.targetSummary().failed());

        final DeployTargetSnapshot target = deployService.getDeployTargets(result.deployKey(), 0, 20, null, null)
                .targets()
                .getFirst();
        assertEquals(DeployTargetStatus.FAILED, target.targetStatus());
        assertEquals(DeployStepStatus.FAILED, target.currentStepStatus());
        assertEquals("SEND_MESSAGE_FAILED", target.lastErrorCode());
        assertNotNull(target.lastErrorMessage());
    }

    @Test
    void startDeployTransitionsReadyDeployToRunning() throws Exception {
        final DeployTraceRegistry traceRegistry = new DeployTraceRegistry(new InMemoryDeployTraceRedisPort());
        final InMemoryDeployStorePort storePort = new InMemoryDeployStorePort();
        final List<UiCommandMessage> publishedMessages = new ArrayList<>();
        deployService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService(tempDir.resolve("missing-scenarios")),
                storePort,
                new InMemoryEqpCrudPort(),
                publishedMessages::add,
                UiBusinessEventPublishPort.noop(),
                traceRegistry
        );

        final DeployCreateResult created = deployService.createDeploy(sendMessageCommand(), "tester");

        final DeployActionResult started = deployService.startDeploy(created.deployKey(), "tester");
        assertEquals(DeployStatus.RUNNING, started.deployStatus());
        assertEquals(Boolean.FALSE, started.pauseRequested());
        assertEquals("DEPLOY_STARTED", started.resultCode());

        final UiCommandMessage firstMessage = awaitPublishedMessage(publishedMessages);
        replyPass(traceRegistry, firstMessage);

        final var summary = awaitTerminalSummary(created.deployKey());
        assertEquals(DeployStatus.COMPLETED, summary.deployStatus());
    }

    @Test
    void holdDeployTransitionsToReadyAtSafePoint() throws Exception {
        final DeployTraceRegistry traceRegistry = new DeployTraceRegistry(new InMemoryDeployTraceRedisPort());
        final InMemoryDeployStorePort storePort = new InMemoryDeployStorePort();
        final InMemoryEqpCrudPort eqpCrudPort = new InMemoryEqpCrudPort();
        eqpCrudPort.put(snapshot("PHOTO_001", 1L, "EQP_PARAM_OLD", "EQP_PARAM_V1"));
        final List<UiCommandMessage> publishedMessages = java.util.Collections.synchronizedList(new ArrayList<>());

        deployService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService(tempDir.resolve("missing-scenarios")),
                storePort,
                eqpCrudPort,
                UiGatewayEventPublishPort.noop(),
                publishedMessages::add,
                traceRegistry
        );

        final DeployCreateResult created = deployService.createDeploy(modelBusinessJarEqpParamRegisterCommand("PHOTO_001"), "tester");
        deployService.startDeploy(created.deployKey(), "tester");
        final UiCommandMessage firstMessage = awaitPublishedMessage(publishedMessages);

        final DeployActionResult holdResult = deployService.holdDeploy(created.deployKey(), "tester");
        assertEquals(DeployStatus.RUNNING, holdResult.deployStatus());
        assertEquals(Boolean.TRUE, holdResult.pauseRequested());
        assertEquals("DEPLOY_HOLD_REQUESTED", holdResult.resultCode());

        replyPass(traceRegistry, firstMessage);

        final var readySummary = awaitReadySummary(created.deployKey());
        assertEquals(DeployStatus.READY, readySummary.deployStatus());
        assertFalse(readySummary.pauseRequested());

        final DeployTargetSnapshot target = deployService.getDeployTargets(created.deployKey(), 0, 20, null, null)
                .targets()
                .getFirst();
        assertEquals(DeployTargetStatus.PENDING, target.targetStatus());
        assertEquals(DeployStepStatus.COMPLETED, target.currentStepStatus());
        assertTrue(publishedMessages.size() >= 1);
    }

    @Test
    void startDeployResumesAfterHoldAndCompletes() throws Exception {
        final DeployTraceRegistry traceRegistry = new DeployTraceRegistry(new InMemoryDeployTraceRedisPort());
        final InMemoryDeployStorePort storePort = new InMemoryDeployStorePort();
        final InMemoryEqpCrudPort eqpCrudPort = new InMemoryEqpCrudPort();
        eqpCrudPort.put(snapshot("PHOTO_001", 1L));
        final List<UiCommandMessage> publishedMessages = java.util.Collections.synchronizedList(new ArrayList<>());

        deployService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService(tempDir.resolve("missing-scenarios")),
                storePort,
                eqpCrudPort,
                UiGatewayEventPublishPort.noop(),
                publishedMessages::add,
                traceRegistry
        );

        final DeployCreateResult created = deployService.createDeploy(modelBusinessJarEqpParamRegisterCommand("PHOTO_001"), "tester");
        deployService.startDeploy(created.deployKey(), "tester");
        final UiCommandMessage firstMessage = awaitPublishedMessage(publishedMessages);
        deployService.holdDeploy(created.deployKey(), "tester");
        replyPass(traceRegistry, firstMessage);
        awaitReadySummary(created.deployKey());

        final int handledCount = publishedMessages.size();
        final DeployActionResult restarted = deployService.startDeploy(created.deployKey(), "tester");
        assertEquals(DeployStatus.RUNNING, restarted.deployStatus());
        assertEquals(Boolean.FALSE, restarted.pauseRequested());

        replyAllNewMessagesAsPass(traceRegistry, publishedMessages, handledCount, created.deployKey());

        final var summary = awaitTerminalSummary(created.deployKey());
        assertEquals(DeployStatus.COMPLETED, summary.deployStatus());
    }

    @Test
    void eqpParamDeployPublishesResolvedItemsPayload() throws Exception {
        final InMemoryEqpCrudPort eqpCrudPort = new InMemoryEqpCrudPort();
        eqpCrudPort.put(snapshot("PHOTO_001", 1L, "EQP_PARAM_OLD", "EQP_PARAM_V1"));

        final DeployServiceCommandSupport commandSupport = new DeployServiceCommandSupport(
                org.mockito.Mockito.mock(DeployStorePort.class),
                eqpCrudPort,
                eqpQueryPort(eqpCrudPort),
                org.mockito.Mockito.mock(com.nori.tc.ui.core.port.db.ModelCrudPort.class),
                modelDetailQueryPort(),
                UiGatewayEventPublishPort.noop(),
                UiBusinessEventPublishPort.noop(),
                objectMapper
        );

        final JsonNode eqpParamMessage = commandSupport.buildDirectStepUiMessage(
                "EXEC-01",
                UiCommandEventType.EQP_REGISTER_DEPLOY,
                "PHOTO_001",
                new com.nori.tc.ui.core.deploy.DeployResolvedStepPlan(
                        1,
                        "UPDATE_EQP_PARAM",
                        "EQP Param Update",
                        com.nori.tc.ui.core.deploy.DeployTargetApp.BUSINESS,
                        "eqp param payload 보강 확인",
                        com.nori.tc.ui.core.deploy.DeployScenarioAction.UPDATE_EQP_PARAM,
                        1000L,
                        true,
                        new com.nori.tc.ui.core.deploy.DeployScenarioRetryPolicy(1, 0L),
                        false,
                        com.nori.tc.ui.core.deploy.DeployRollbackMode.MANUAL_ONLY,
                        null
                ),
                "TRACE-01",
                "tester",
                eqpParamUpdateCommand("PHOTO_001").commonRequest().uiMessage()
        );

        assertEquals("EQP_PARAM_V1", eqpParamMessage.path("eqpParams").path("version").asString());
        assertEquals("PPID", eqpParamMessage.path("eqpParams").path("items").get(0).path("name").asString());
        assertEquals("P1001", eqpParamMessage.path("eqpParams").path("items").get(0).path("value").asString());
    }

    @Test
    void holdDeployRejectsDuplicateHoldRequest() throws Exception {
        final DeployTraceRegistry traceRegistry = new DeployTraceRegistry(new InMemoryDeployTraceRedisPort());
        final InMemoryDeployStorePort storePort = new InMemoryDeployStorePort();
        final InMemoryEqpCrudPort eqpCrudPort = new InMemoryEqpCrudPort();
        eqpCrudPort.put(snapshot("PHOTO_001", 1L));
        final List<UiCommandMessage> publishedMessages = java.util.Collections.synchronizedList(new ArrayList<>());

        deployService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService(tempDir.resolve("missing-scenarios")),
                storePort,
                eqpCrudPort,
                UiGatewayEventPublishPort.noop(),
                publishedMessages::add,
                traceRegistry
        );

        final DeployCreateResult created = deployService.createDeploy(businessJarUpdateCommand("PHOTO_001"), "tester");
        deployService.startDeploy(created.deployKey(), "tester");
        awaitPublishedMessage(publishedMessages);
        deployService.holdDeploy(created.deployKey(), "tester");

        assertThrows(UiConflictException.class, () -> deployService.holdDeploy(created.deployKey(), "tester"));
    }

    @Test
    void createDeployRejectsWhenAnotherActiveDeployHasSameEqp() {
        final DeployTraceRegistry traceRegistry = new DeployTraceRegistry(new InMemoryDeployTraceRedisPort());
        final InMemoryDeployStorePort storePort = new InMemoryDeployStorePort();
        deployService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService(tempDir.resolve("missing-scenarios")),
                storePort,
                new InMemoryEqpCrudPort(),
                UiGatewayEventPublishPort.noop(),
                UiBusinessEventPublishPort.noop(),
                traceRegistry
        );

        deployService.createDeploy(sendMessageCommand(), "tester");

        final UiConflictException exception =
                assertThrows(UiConflictException.class, () -> deployService.createDeploy(sendMessageCommand(), "tester"));
        assertEquals(UiErrorCode.DEPLOY_ACTIVE_EQP_CONFLICT, exception.errorCode());
        assertTrue(exception.getMessage().contains("eqpId=PHOTO_001"));
    }

    @Test
    void redeployRejectsWhenAnotherActiveDeployHasSameEqp() throws Exception {
        final DeployTraceRegistry traceRegistry = new DeployTraceRegistry(new InMemoryDeployTraceRedisPort());
        final InMemoryDeployStorePort storePort = new InMemoryDeployStorePort();
        deployService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService(tempDir.resolve("missing-scenarios")),
                storePort,
                new InMemoryEqpCrudPort(),
                passGatewayPublisher(traceRegistry),
                UiBusinessEventPublishPort.noop(),
                traceRegistry
        );

        final DeployCreateResult originalDeploy = deployService.createDeploy(sendMessageCommand(), "tester");
        executeReadyDeploy(storePort, originalDeploy.deployKey(), "tester");
        awaitTerminalSummary(originalDeploy.deployKey());

        final DeployCreateResult activeDeploy = deployService.createDeploy(sendMessageCommand(), "tester");
        assertEquals(DeployStatus.READY, activeDeploy.deployStatus());

        final UiConflictException exception =
                assertThrows(UiConflictException.class, () -> deployService.redeploy(originalDeploy.deployKey(), "tester"));
        assertEquals(UiErrorCode.DEPLOY_ACTIVE_EQP_CONFLICT, exception.errorCode());
        assertTrue(exception.getMessage().contains("eqpId=PHOTO_001"));
        assertTrue(exception.getMessage().contains("activeDeployKey=" + activeDeploy.deployKey()));
    }

    @Test
    void startDeployRejectsWhenAnotherActiveDeployHasSameEqp() {
        final DeployTraceRegistry traceRegistry = new DeployTraceRegistry(new InMemoryDeployTraceRedisPort());
        final InMemoryDeployStorePort storePort = new InMemoryDeployStorePort();
        final DeployPlanningService planningService = planningService(tempDir.resolve("missing-scenarios"));
        deployService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService,
                storePort,
                new InMemoryEqpCrudPort(),
                UiGatewayEventPublishPort.noop(),
                UiBusinessEventPublishPort.noop(),
                traceRegistry
        );

        final DeployCreateResult readyDeploy = deployService.createDeploy(sendMessageCommand(), "tester");
        final PersistedDeploy injectedDeploy = storePort.createDeploy(
                sendMessageCommand(),
                planningService.plan(sendMessageCommand()),
                "injector"
        );
        storePort.updateDeployStatus(injectedDeploy.deployKey(), DeployStatus.RUNNING, false, null, null);

        final UiConflictException exception =
                assertThrows(UiConflictException.class, () -> deployService.startDeploy(readyDeploy.deployKey(), "tester"));
        assertEquals(UiErrorCode.DEPLOY_ACTIVE_EQP_CONFLICT, exception.errorCode());
        assertTrue(exception.getMessage().contains("eqpId=PHOTO_001"));
        assertTrue(exception.getMessage().contains("activeDeployKey=" + injectedDeploy.deployKey()));
    }

    @Test
    void businessJarUpdateRollsBackOnlyFailedTargetAndKeepsOtherTargetCompleted() throws Exception {
        final DeployTraceRegistry traceRegistry = new DeployTraceRegistry(new InMemoryDeployTraceRedisPort());
        final InMemoryDeployStorePort storePort = new InMemoryDeployStorePort();
        final InMemoryEqpCrudPort eqpCrudPort = new InMemoryEqpCrudPort();
        eqpCrudPort.put(snapshot("PHOTO_001", 1L));
       eqpCrudPort.put(snapshot("PHOTO_002", 2L));
        final List<UiCommandMessage> publishedMessages = new ArrayList<>();

        deployService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService(tempDir.resolve("missing-scenarios")),
                storePort,
                eqpCrudPort,
                UiGatewayEventPublishPort.noop(),
                businessPublisherForRollbackSuccess(traceRegistry, publishedMessages),
                traceRegistry
        );

        final DeployCreateResult result = deployService.createDeploy(
                modelBusinessJarEqpParamRegisterCommand("PHOTO_001", "PHOTO_002"),
                "tester"
        );
        executeReadyDeploy(storePort, result.deployKey(), "tester");
        final var summary = awaitTerminalSummary(result.deployKey());

        assertEquals(DeployStatus.PARTIAL_FAILED, summary.deployStatus());
        assertEquals(1, summary.targetSummary().completed());
        assertEquals(1, summary.targetSummary().rolledBack());

        final DeployTargetPage targetPage = deployService.getDeployTargets(result.deployKey(), 0, 20, null, null);
        final DeployTargetSnapshot first = targetPage.targets().stream()
                .filter(item -> item.eqpId().equals("PHOTO_001"))
                .findFirst()
                .orElseThrow();
        final DeployTargetSnapshot second = targetPage.targets().stream()
                .filter(item -> item.eqpId().equals("PHOTO_002"))
                .findFirst()
                .orElseThrow();

        assertEquals(DeployTargetStatus.COMPLETED, first.targetStatus());
        assertEquals(DeployTargetStatus.ROLLED_BACK, second.targetStatus());
        assertEquals("EQP_PARAM_UPDATE_FAILED", second.lastErrorCode());

        final List<DeployStoredSnapshot> stepSnapshots = storePort.findSnapshots(second.deployTargetKey(), DeploySnapshotScope.STEP_PRE_STATE);
        assertFalse(stepSnapshots.isEmpty());
        final JsonNode targetPreState = storePort.findLatestTargetSnapshot(
                        second.deployTargetKey(),
                        DeploySnapshotScope.TARGET_PRE_STATE,
                        null,
                        DeploySnapshotKind.TARGET_FULL_STATE
                )
                .orElseThrow()
                .snapshotData();
        assertTrue(targetPreState.path("eqpExists").asBoolean());
        assertNotNull(targetPreState.path("businessJar").path("expectedSha256").asString(null));
        assertTrue(publishedMessages.stream()
                .anyMatch(message -> message.eqpId().equals("PHOTO_002")
                        && message.eventType() == UiCommandEventType.EQP_MODEL_UPDATE
                        && "legacy-business.jar".equals(message.uiMessage().path("businessJar").path("jarFileName").asString(null))));
    }

    @Test
    void compensationFailureMarksTargetCompensationFailed() throws Exception {
        final DeployTraceRegistry traceRegistry = new DeployTraceRegistry(new InMemoryDeployTraceRedisPort());
        final InMemoryDeployStorePort storePort = new InMemoryDeployStorePort();
        final InMemoryEqpCrudPort eqpCrudPort = new InMemoryEqpCrudPort();
        eqpCrudPort.put(snapshot("PHOTO_002", 2L));

        deployService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService(tempDir.resolve("missing-scenarios")),
                storePort,
                eqpCrudPort,
                UiGatewayEventPublishPort.noop(),
                businessPublisherForCompensationFailure(traceRegistry),
                traceRegistry
        );

        final DeployCreateResult result = deployService.createDeploy(
                modelBusinessJarEqpParamRegisterCommand("PHOTO_002"),
                "tester"
        );
        executeReadyDeploy(storePort, result.deployKey(), "tester");
        final var summary = awaitTerminalSummary(result.deployKey());

        assertEquals(DeployStatus.FAILED, summary.deployStatus());
        assertEquals("DEPLOY_FAILED", summary.resultCode());
        assertEquals(1, summary.targetSummary().compensationFailed());

        final DeployTargetSnapshot target = deployService.getDeployTargets(result.deployKey(), 0, 20, null, null)
                .targets()
                .getFirst();
        assertEquals(DeployTargetStatus.COMPENSATION_FAILED, target.targetStatus());
        assertEquals(DeployStepStatus.COMPENSATION_FAILED, target.currentStepStatus());
        assertEquals("PLUGIN_ROLLBACK_FAILED", target.lastErrorCode());
        assertTrue(target.rollbackRequired());
    }

    @Test
    void getDeployHistoryReturnsOnlyNonTerminalRowsWhenTerminalOnlyIsFalse() {
        final DeployTraceRegistry traceRegistry = new DeployTraceRegistry(new InMemoryDeployTraceRedisPort());
        final InMemoryDeployStorePort storePort = new InMemoryDeployStorePort();

        deployService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService(tempDir.resolve("missing-scenarios")),
                storePort,
                new InMemoryEqpCrudPort(),
                UiGatewayEventPublishPort.noop(),
                UiBusinessEventPublishPort.noop(),
                traceRegistry
        );

        final DeployCreateResult runningDeploy = deployService.createDeploy(sendMessageCommand(), "tester");
        final DeployCreateResult completedDeploy = deployService.createDeploy(businessJarUpdateCommand("PHOTO_002"), "tester");
        storePort.updateDeployStatus(
                completedDeploy.deployKey(),
                DeployStatus.COMPLETED,
                false,
                null,
                null
        );

        final PagedResponse<DeployHistoryView> page = deployService.getDeployHistory(
                null,
                null,
                null,
                null,
                false,
                UiPageRequest.of(0, 20)
        );

        assertEquals(1, page.items().size());
        assertEquals(runningDeploy.deployKey(), page.items().getFirst().deployKey());
        assertEquals(DeployStatus.READY, page.items().getFirst().deployStatus());
    }

    @Test
    void replyHandledByDifferentInstanceCompletesDeploy() throws Exception {
        final InMemoryDeployTraceRedisPort sharedTracePort = new InMemoryDeployTraceRedisPort();
        final InMemoryDeployExecutionCoordinationPort sharedCoordinationPort =
                new InMemoryDeployExecutionCoordinationPort();
        final DeployTraceRegistry instanceOneTraceRegistry = new DeployTraceRegistry(sharedTracePort);
        final DeployTraceRegistry instanceTwoTraceRegistry = new DeployTraceRegistry(sharedTracePort);
        final InMemoryDeployStorePort storePort = new InMemoryDeployStorePort();
        final List<UiCommandMessage> publishedMessages = new ArrayList<>();

        deployService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService(tempDir.resolve("missing-scenarios")),
                storePort,
                new InMemoryEqpCrudPort(),
                message -> publishedMessages.add(message),
                UiBusinessEventPublishPort.noop(),
                instanceOneTraceRegistry,
                sharedCoordinationPort
        );
        final DeployExecutor primaryDeployExecutor = deployExecutor;
        final DeployService otherInstanceService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService(tempDir.resolve("missing-scenarios")),
                new InMemoryDeployStorePort(),
                new InMemoryEqpCrudPort(),
                UiGatewayEventPublishPort.noop(),
                UiBusinessEventPublishPort.noop(),
                instanceTwoTraceRegistry,
                sharedCoordinationPort
        );

        try {
            final DeployCreateResult result = deployService.createDeploy(sendMessageCommand(), "tester");
            executeReadyDeployAsync(primaryDeployExecutor, storePort, result.deployKey(), "tester");
            final UiCommandMessage publishedMessage = awaitPublishedMessage(publishedMessages);

            otherInstanceService.handleReply(new UiCommandReply(
                    publishedMessage.traceId(),
                    "TC-GATEWAY-APP",
                    publishedMessage.eventType().name() + "_REP",
                    publishedMessage.eqpId(),
                    publishedMessage.interfaceType(),
                    UiTaskStatus.PASS,
                    null,
                    null
            ));

            final var summary = awaitTerminalSummary(result.deployKey());
            assertEquals(DeployStatus.COMPLETED, summary.deployStatus());
            assertEquals("DEPLOY_COMPLETED", summary.resultCode());
        } finally {
            otherInstanceService.shutdown();
        }
    }

    @Test
    void cancelDeployMarksRunningDeployCanceled() throws Exception {
        final DeployTraceRegistry traceRegistry = new DeployTraceRegistry(new InMemoryDeployTraceRedisPort());
        final InMemoryDeployStorePort storePort = new InMemoryDeployStorePort();
        final List<UiCommandMessage> publishedMessages = new ArrayList<>();

        deployService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService(tempDir.resolve("missing-scenarios")),
                storePort,
                new InMemoryEqpCrudPort(),
                message -> publishedMessages.add(message),
                UiBusinessEventPublishPort.noop(),
                traceRegistry
        );

        final DeployCreateResult result = deployService.createDeploy(sendMessageCommand(), "tester");
        executeReadyDeployAsync(storePort, result.deployKey(), "tester");
        final UiCommandMessage publishedMessage = awaitPublishedMessage(publishedMessages);
        assertNotNull(publishedMessage.traceId());

        final DeployActionResult cancelResult = deployService.cancelDeploy(result.deployKey(), "tester");
        assertEquals(DeployStatus.CANCELED, cancelResult.deployStatus());
        assertEquals("DEPLOY_CANCELED", cancelResult.resultCode());

        final var summary = awaitTerminalSummary(result.deployKey());
        assertEquals(DeployStatus.CANCELED, summary.deployStatus());
        assertEquals("DEPLOY_CANCELED", summary.resultCode());
        assertEquals(1, summary.targetSummary().canceled());

        final DeployTargetSnapshot target = deployService.getDeployTargets(result.deployKey(), 0, 20, null, null)
                .targets()
                .getFirst();
        assertEquals(DeployTargetStatus.CANCELED, target.targetStatus());
        assertEquals(DeployStepStatus.CANCELED, target.currentStepStatus());
    }

    @Test
    void cancelDeployRejectsTerminalDeploy() throws Exception {
        final DeployTraceRegistry traceRegistry = new DeployTraceRegistry(new InMemoryDeployTraceRedisPort());
        final InMemoryDeployStorePort storePort = new InMemoryDeployStorePort();
        deployService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService(tempDir.resolve("missing-scenarios")),
                storePort,
                new InMemoryEqpCrudPort(),
                passGatewayPublisher(traceRegistry),
                UiBusinessEventPublishPort.noop(),
                traceRegistry
        );

        final DeployCreateResult result = deployService.createDeploy(sendMessageCommand(), "tester");
        executeReadyDeploy(storePort, result.deployKey(), "tester");
        awaitTerminalSummary(result.deployKey());

        assertThrows(UiConflictException.class, () -> deployService.cancelDeploy(result.deployKey(), "tester"));
    }

    @Test
    void redeployCreatesNewDeployFromTerminalDeploy() throws Exception {
        final DeployTraceRegistry traceRegistry = new DeployTraceRegistry(new InMemoryDeployTraceRedisPort());
        final InMemoryDeployStorePort storePort = new InMemoryDeployStorePort();
        deployService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService(tempDir.resolve("missing-scenarios")),
                storePort,
                new InMemoryEqpCrudPort(),
                passGatewayPublisher(traceRegistry),
                UiBusinessEventPublishPort.noop(),
                traceRegistry
        );

        final DeployCreateResult originalResult = deployService.createDeploy(sendMessageCommand(), "tester");
        executeReadyDeploy(storePort, originalResult.deployKey(), "tester");
        awaitTerminalSummary(originalResult.deployKey());

        final DeployCreateResult redeployResult = deployService.redeploy(originalResult.deployKey(), "tester");
        assertTrue(redeployResult.deployKey() > originalResult.deployKey());
        assertEquals(DeployStatus.READY, redeployResult.deployStatus());
        assertEquals("DEPLOY_CREATED", redeployResult.resultCode());

        final var summary = deployService.getDeploySummary(redeployResult.deployKey());
        assertEquals(DeployStatus.READY, summary.deployStatus());
        assertNull(summary.startedAt());
        assertNull(summary.finishedAt());
    }

    @Test
    void deleteDeployRemovesReadyDeployBeforeExecution() throws Exception {
        final DeployTraceRegistry traceRegistry = new DeployTraceRegistry(new InMemoryDeployTraceRedisPort());
        final InMemoryDeployStorePort storePort = new InMemoryDeployStorePort();
        deployService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService(tempDir.resolve("missing-scenarios")),
                storePort,
                new InMemoryEqpCrudPort(),
                passGatewayPublisher(traceRegistry),
                UiBusinessEventPublishPort.noop(),
                traceRegistry
        );

        final DeployCreateResult result = deployService.createDeploy(sendMessageCommand(), "tester");

        final DeployDeleteResult deleteResult = deployService.deleteDeploy(result.deployKey(), "tester");
        assertTrue(deleteResult.deleted());

        assertThrows(UiNotFoundException.class, () -> deployService.getDeploySummary(result.deployKey()));
    }

    @Test
    void deleteDeployRejectsRunningDeploy() throws Exception {
        final DeployTraceRegistry traceRegistry = new DeployTraceRegistry(new InMemoryDeployTraceRedisPort());
        final InMemoryDeployStorePort storePort = new InMemoryDeployStorePort();
        final List<UiCommandMessage> publishedMessages = new ArrayList<>();
        deployService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService(tempDir.resolve("missing-scenarios")),
                storePort,
                new InMemoryEqpCrudPort(),
                publishedMessages::add,
                UiBusinessEventPublishPort.noop(),
                traceRegistry
        );

        final DeployCreateResult created = deployService.createDeploy(sendMessageCommand(), "tester");
        deployService.startDeploy(created.deployKey(), "tester");

        assertThrows(UiConflictException.class, () -> deployService.deleteDeploy(created.deployKey(), "tester"));
        assertEquals(DeployStatus.RUNNING, deployService.getDeploySummary(created.deployKey()).deployStatus());
    }

    @Test
    void deleteDeployRemovesTerminalDeploy() throws Exception {
        final DeployTraceRegistry traceRegistry = new DeployTraceRegistry(new InMemoryDeployTraceRedisPort());
        final InMemoryDeployStorePort storePort = new InMemoryDeployStorePort();
        deployService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService(tempDir.resolve("missing-scenarios")),
                storePort,
                new InMemoryEqpCrudPort(),
                passGatewayPublisher(traceRegistry),
                UiBusinessEventPublishPort.noop(),
                traceRegistry
        );

        final DeployCreateResult result = deployService.createDeploy(sendMessageCommand(), "tester");
        executeReadyDeploy(storePort, result.deployKey(), "tester");
        awaitTerminalSummary(result.deployKey());

        final DeployDeleteResult deleteResult = deployService.deleteDeploy(result.deployKey(), "tester");
        assertTrue(deleteResult.deleted());

        assertThrows(UiNotFoundException.class, () -> deployService.getDeploySummary(result.deployKey()));
    }

    @Test
    void registerModelDeployUpdatesEqpModelVersionAfterBusinessReplyPass() throws Exception {
        final DeployTraceRegistry traceRegistry = new DeployTraceRegistry(new InMemoryDeployTraceRedisPort());
        final InMemoryDeployStorePort storePort = new InMemoryDeployStorePort();
        final InMemoryEqpCrudPort eqpCrudPort = new InMemoryEqpCrudPort();
        final List<UiCommandMessage> publishedMessages = new ArrayList<>();
        eqpCrudPort.put(snapshot("PHOTO_001", 1L));

        deployService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService(tempDir.resolve("missing-scenarios")),
                storePort,
                eqpCrudPort,
                UiGatewayEventPublishPort.noop(),
                publishedMessages::add,
                traceRegistry
        );

        final DeployCreateResult created = deployService.createDeploy(modelUpdateCommand("PHOTO_001"), "tester");
        deployService.startDeploy(created.deployKey(), "tester");

        final UiCommandMessage message = awaitPublishedMessage(publishedMessages);
        assertEquals(UiCommandEventType.EQP_MODEL_UPDATE, message.eventType());

        replyPass(traceRegistry, message);

        final var summary = awaitTerminalSummary(created.deployKey());
        assertEquals(DeployStatus.COMPLETED, summary.deployStatus());
        assertEquals(1002L, eqpCrudPort.findSnapshotByEqpId("PHOTO_001").orElseThrow().eqp().modelVersionKey());
    }

    @Test
    void businessJarDeployUpdatesEqpBusinessJarAfterBusinessReplyPass() throws Exception {
        final DeployTraceRegistry traceRegistry = new DeployTraceRegistry(new InMemoryDeployTraceRedisPort());
        final InMemoryDeployStorePort storePort = new InMemoryDeployStorePort();
        final InMemoryEqpCrudPort eqpCrudPort = new InMemoryEqpCrudPort();
        final List<UiCommandMessage> publishedMessages = new ArrayList<>();
        eqpCrudPort.put(snapshot("PHOTO_001", 1L));

        deployService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService(tempDir.resolve("missing-scenarios")),
                storePort,
                eqpCrudPort,
                UiGatewayEventPublishPort.noop(),
                publishedMessages::add,
                traceRegistry
        );

        final DeployCreateResult created = deployService.createDeploy(businessJarUpdateCommand("PHOTO_001"), "tester");
        deployService.startDeploy(created.deployKey(), "tester");

        final UiCommandMessage message = awaitPublishedMessage(publishedMessages);
        assertEquals(UiCommandEventType.EQP_BUSINESS_JARFILE_UPDATE, message.eventType());

        replyPass(traceRegistry, message);

        final var summary = awaitTerminalSummary(created.deployKey());
        assertEquals(DeployStatus.COMPLETED, summary.deployStatus());
        assertEquals(9002L, eqpCrudPort.findSnapshotByEqpId("PHOTO_001").orElseThrow().eqp().businessJarKey());
    }

    @Test
    void eqpParamRegisterDeployUpdatesAppliedParamVersionAfterBusinessReplyPass() throws Exception {
        final DeployTraceRegistry traceRegistry = new DeployTraceRegistry(new InMemoryDeployTraceRedisPort());
        final InMemoryDeployStorePort storePort = new InMemoryDeployStorePort();
        final InMemoryEqpCrudPort eqpCrudPort = new InMemoryEqpCrudPort();
        final List<UiCommandMessage> publishedMessages = new ArrayList<>();
        eqpCrudPort.put(snapshot("PHOTO_001", 1L, "EQP_PARAM_OLD", "EQP_PARAM_V2"));

        deployService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService(tempDir.resolve("missing-scenarios")),
                storePort,
                eqpCrudPort,
                UiGatewayEventPublishPort.noop(),
                publishedMessages::add,
                traceRegistry
        );

        final DeployCreateResult created = deployService.createDeploy(eqpParamUpdateCommandWithVersion("EQP_PARAM_V2", "PHOTO_001"), "tester");
        deployService.startDeploy(created.deployKey(), "tester");

        final UiCommandMessage message = awaitPublishedMessage(publishedMessages);
        assertEquals(UiCommandEventType.EQP_PARAMETER_UPDATE, message.eventType());

        replyPass(traceRegistry, message);

        final var summary = awaitTerminalSummary(created.deployKey());
        assertEquals(DeployStatus.COMPLETED, summary.deployStatus());
        assertEquals("EQP_PARAM_V2", eqpCrudPort.findSnapshotByEqpId("PHOTO_001").orElseThrow().eqp().appliedParamVersion());
    }

    @Test
    void modelBusinessJarDeployUpdatesEqpModelAndBusinessJarAfterBusinessReplyPass() throws Exception {
        final DeployTraceRegistry traceRegistry = new DeployTraceRegistry(new InMemoryDeployTraceRedisPort());
        final InMemoryDeployStorePort storePort = new InMemoryDeployStorePort();
        final InMemoryEqpCrudPort eqpCrudPort = new InMemoryEqpCrudPort();
        final List<UiCommandMessage> publishedMessages = new ArrayList<>();
        eqpCrudPort.put(snapshot("PHOTO_001", 1L));

        deployService = newDeployService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                planningService(tempDir.resolve("missing-scenarios")),
                storePort,
                eqpCrudPort,
                UiGatewayEventPublishPort.noop(),
                publishedMessages::add,
                traceRegistry
        );

        final DeployCreateResult created = deployService.createDeploy(modelBusinessJarRegisterCommand("PHOTO_001"), "tester");
        deployService.startDeploy(created.deployKey(), "tester");

        final UiCommandMessage message = awaitPublishedMessage(publishedMessages);
        assertEquals(UiCommandEventType.EQP_MODEL_UPDATE, message.eventType());

        replyPass(traceRegistry, message);

        final var summary = awaitTerminalSummary(created.deployKey());
        assertEquals(DeployStatus.COMPLETED, summary.deployStatus());
        final var snapshot = eqpCrudPort.findSnapshotByEqpId("PHOTO_001").orElseThrow().eqp();
        assertEquals(1002L, snapshot.modelVersionKey());
        assertEquals(9002L, snapshot.businessJarKey());
    }

    private DeployService newDeployService(
            final UiDeployProperties deployProperties,
            final DeployPlanningService planningService,
            final InMemoryDeployStorePort storePort,
            final InMemoryEqpCrudPort eqpCrudPort,
            final UiGatewayEventPublishPort gatewayEventPublishPort,
            final UiBusinessEventPublishPort businessEventPublishPort,
            final DeployTraceRegistry traceRegistry
    ) {
        return newDeployService(
                deployProperties,
                planningService,
                storePort,
                eqpCrudPort,
                gatewayEventPublishPort,
                businessEventPublishPort,
                traceRegistry,
                new InMemoryDeployExecutionCoordinationPort()
        );
    }

    private DeployService newDeployService(
            final UiDeployProperties deployProperties,
            final DeployPlanningService planningService,
            final InMemoryDeployStorePort storePort,
            final InMemoryEqpCrudPort eqpCrudPort,
            final UiGatewayEventPublishPort gatewayEventPublishPort,
            final UiBusinessEventPublishPort businessEventPublishPort,
            final DeployTraceRegistry traceRegistry,
            final InMemoryDeployExecutionCoordinationPort coordinationPort
    ) {
        final DeployServiceCommandSupport commandSupport = new DeployServiceCommandSupport(
                storePort,
                eqpCrudPort,
                eqpQueryPort(eqpCrudPort),
                org.mockito.Mockito.mock(com.nori.tc.ui.core.port.db.ModelCrudPort.class),
                modelDetailQueryPort(),
                gatewayEventPublishPort,
                businessEventPublishPort,
                objectMapper
        );
        final DeployPlanner deployPlanner = new DeployPlanner(planningService);
        final DeployTraceRecorder deployTraceRecorder = new DeployTraceRecorder(traceRegistry);
        final DeployCommandAssembler commandAssembler = new DeployCommandAssembler(commandSupport);
        final DeployRollbackCoordinator rollbackCoordinator =
                new DeployRollbackCoordinator(storePort, commandAssembler, deployTraceRecorder);
        final DeployEqpFinalizationService deployEqpFinalizationService =
                new DeployEqpFinalizationService(new EqpPersistenceService(eqpCrudPort));
        final DeployExecutor deployExecutor =
                new DeployExecutor(
                        storePort,
                        commandAssembler,
                        deployTraceRecorder,
                        rollbackCoordinator,
                        deployEqpFinalizationService,
                        coordinationPort,
                        deployProperties
                );
        this.deployExecutor = deployExecutor;
        return new DeployService(
                deployProperties,
                deployPlanner,
                storePort,
                eqpCrudPort,
                deployTraceRecorder,
                commandAssembler,
                deployExecutor
        );
    }

    private ModelDetailQueryPort modelDetailQueryPort() {
        return new ModelDetailQueryPort() {
            @Override
            public List<UiModelParam> findParamsByModelVersionKey(final long modelVersionKey) {
                return List.of(new UiModelParam(
                        modelVersionKey,
                        modelVersionKey,
                        "M_TEMP",
                        "VALUE-" + modelVersionKey,
                        null,
                        OffsetDateTime.now(ZoneOffset.UTC)
                ));
            }

            @Override
            public List<com.nori.tc.ui.domain.model.UiModelSecsMessage> findSecsMessagesByModelVersionKey(final long modelVersionKey) {
                return List.of();
            }

            @Override
            public List<com.nori.tc.ui.domain.model.UiModelSocketMessage> findSocketMessagesByModelVersionKey(final long modelVersionKey) {
                return List.of();
            }

            @Override
            public List<com.nori.tc.ui.domain.model.UiModelMesMessage> findMesMessagesByModelVersionKey(final long modelVersionKey) {
                return List.of();
            }

            @Override
            public List<com.nori.tc.ui.domain.model.UiModelVariableId> findVariableIdsByModelVersionKey(final long modelVersionKey) {
                return List.of();
            }

            @Override
            public List<com.nori.tc.ui.domain.model.UiModelReportId> findReportIdsByModelVersionKey(final long modelVersionKey) {
                return List.of();
            }

            @Override
            public List<com.nori.tc.ui.domain.model.UiModelEventId> findEventIdsByModelVersionKey(final long modelVersionKey) {
                return List.of();
            }

            @Override
            public List<com.nori.tc.ui.domain.model.UiModelMessageFlow> findMessageFlowsByModelVersionKey(final long modelVersionKey) {
                return List.of();
            }

            @Override
            public Optional<com.nori.tc.ui.domain.model.UiModelMdf> findMdfByModelVersionKey(final long modelVersionKey) {
                return Optional.empty();
            }

            @Override
            public List<com.nori.tc.ui.domain.model.UiModelDcopItem> findDcopItemsByModelVersionKey(final long modelVersionKey) {
                return List.of();
            }

            @Override
            public List<com.nori.tc.ui.domain.model.UiModelPlcGroup> findPlcGroupsByModelVersionKey(final long modelVersionKey) {
                return List.of();
            }

            @Override
            public List<com.nori.tc.ui.domain.model.UiModelPlcRegister> findPlcRegistersByModelVersionKey(final long modelVersionKey) {
                return List.of();
            }
        };
    }

    private EqpQueryPort eqpQueryPort(final InMemoryEqpCrudPort eqpCrudPort) {
        return new EqpQueryPort() {
            @Override
            public PagedResponse<UiEqp> findAll(final UiPageRequest pageRequest) {
                return PagedResponse.empty(0, 1);
            }

            @Override
            public Optional<UiEqp> findByEqpId(final String eqpId) {
                return eqpCrudPort.findSnapshotByEqpId(eqpId).map(EqpManagementSnapshot::eqp);
            }

            @Override
            public List<String> findParamVersionsByEqpId(final String eqpId) {
                return eqpCrudPort.findSnapshotByEqpId(eqpId)
                        .stream()
                        .flatMap(snapshot -> snapshot.params().stream())
                        .map(UiEqpParam::paramVersion)
                        .filter(version -> version != null && !version.isBlank())
                        .distinct()
                        .toList();
            }

            @Override
            public List<UiEqpParam> findParamsByEqpIdAndVersion(final String eqpId, final String version) {
                return eqpCrudPort.findSnapshotByEqpId(eqpId)
                        .stream()
                        .flatMap(snapshot -> snapshot.params().stream())
                        .filter(param -> Objects.equals(param.paramVersion(), version))
                        .toList();
            }

            @Override
            public Optional<EqpCheckoutStateView> findCheckoutStateByEqpId(final String eqpId) {
                return Optional.empty();
            }

            @Override
            public Optional<EqpRuntimeStateView> findRuntimeStateByEqpId(final String eqpId) {
                return Optional.empty();
            }
        };
    }

    private void executeReadyDeploy(
            final InMemoryDeployStorePort storePort,
            final long deployKey,
            final String actor
    ) {
        final InMemoryDeployStorePort.DeployExecutionSeed seed = storePort.executionSeed(deployKey);
        storePort.updateDeployStatus(deployKey, DeployStatus.RUNNING, false, null, null);
        seed.executableTargets().forEach(target -> deployExecutor.executeTarget(
                deployKey,
                seed.eventType(),
                target,
                actor
        ));
    }

    private void executeReadyDeployAsync(
            final DeployExecutor executor,
            final InMemoryDeployStorePort storePort,
            final long deployKey,
            final String actor
    ) {
        final InMemoryDeployStorePort.DeployExecutionSeed seed = storePort.executionSeed(deployKey);
        storePort.updateDeployStatus(deployKey, DeployStatus.RUNNING, false, null, null);
        for (DeployExecutionTarget target : seed.executableTargets()) {
            final Thread executionThread = new Thread(
                    () -> executor.executeTarget(deployKey, seed.eventType(), target, actor),
                    "deploy-test-" + deployKey + "-" + target.target().deployTargetKey()
            );
            executionThread.start();
        }
    }

    private void executeReadyDeployAsync(
            final InMemoryDeployStorePort storePort,
            final long deployKey,
            final String actor
    ) {
        executeReadyDeployAsync(deployExecutor, storePort, deployKey, actor);
    }

    private UiCommandMessage awaitPublishedMessage(final List<UiCommandMessage> publishedMessages) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (!publishedMessages.isEmpty()) {
                return publishedMessages.getFirst();
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("deploy command publish 대기 중 timeout이 발생했습니다.");
    }

    private void replyAllNewMessagesAsPass(
            final DeployTraceRegistry traceRegistry,
            final List<UiCommandMessage> publishedMessages,
            final int initialHandledCount,
            final long deployKey
    ) throws Exception {
        int handledCount = initialHandledCount;
        for (int idleAttempt = 0; idleAttempt < 50; idleAttempt++) {
            while (publishedMessages.size() > handledCount) {
                replyPass(traceRegistry, publishedMessages.get(handledCount));
                handledCount++;
                idleAttempt = 0;
            }
            final var summary = deployService.getDeploySummary(deployKey);
            if (summary.terminal()) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("resume 이후 추가 publish completion 대기 중 timeout이 발생했습니다.");
    }

    private void replyPass(
            final DeployTraceRegistry traceRegistry,
            final UiCommandMessage message
    ) {
        final String targetApp = message.uiMessage().path("_deploy").path("targetApp").asString();
        final String source = "BUSINESS".equals(targetApp) ? "TC-BUSINESS-APP" : "TC-GATEWAY-APP";
        traceRegistry.handle(new UiCommandReply(
                message.traceId(),
                source,
                message.eventType().name() + "_REP",
                message.eqpId(),
                message.interfaceType(),
                UiTaskStatus.PASS,
                null,
                null
        ));
    }

    private DeployPlanningService planningService(final Path scenarioDirectory) {
        final DeployScenarioCatalogService catalogService = new DeployScenarioCatalogService(
                deployProperties(scenarioDirectory),
                new InMemoryCatalogMetaPort(),
                objectMapper
        );
        catalogService.initializeCatalog();

        return new DeployPlanningService(
                deployProperties(scenarioDirectory),
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
                        equipmentProfile(eqpId)
                )),
                catalogService,
                objectMapper
        );
    }

    private UiEquipmentProfileSnapshot equipmentProfile(final String eqpId) {
        return new UiEquipmentProfileSnapshot(
                1L,
                eqpId,
                "SOCKET",
                "TEST01",
                null,
                "10.10.10.1",
                6000,
                9001L,
                3001L,
                4001L,
                "ACTIVE",
                1,
                true,
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                OffsetDateTime.now(ZoneOffset.UTC).toString(),
                "ENDED",
                null
        );
    }

    private UiGatewayEventPublishPort passGatewayPublisher(final DeployTraceRegistry traceRegistry) {
        return message -> traceRegistry.handle(new UiCommandReply(
                message.traceId(),
                "TC-GATEWAY-APP",
                message.eventType().name() + "_REP",
                message.eqpId(),
                message.interfaceType(),
                UiTaskStatus.PASS,
                null,
                null
        ));
    }

    private UiGatewayEventPublishPort failGatewayPublisher(final DeployTraceRegistry traceRegistry) {
        return message -> traceRegistry.handle(new UiCommandReply(
                message.traceId(),
                "TC-GATEWAY-APP",
                message.eventType().name() + "_REP",
                message.eqpId(),
                message.interfaceType(),
                UiTaskStatus.FAIL,
                "SEND_MESSAGE_FAILED",
                "설비 메시지 송신에 실패했습니다."
        ));
    }

    private UiBusinessEventPublishPort businessPublisherForRollbackSuccess(
            final DeployTraceRegistry traceRegistry,
            final List<UiCommandMessage> publishedMessages
    ) {
        final Map<String, Integer> eventCounts = new HashMap<>();
        return message -> {
            publishedMessages.add(message);
            final String key = message.eqpId() + ":" + message.eventType().name();
            final int count = incrementEventCount(eventCounts, key);

            final UiTaskStatus status;
            final String errorCode;
            final String errorMessage;
            if ("PHOTO_002".equals(message.eqpId()) && message.eventType() == UiCommandEventType.EQP_PARAMETER_UPDATE && count == 1) {
                status = UiTaskStatus.FAIL;
                errorCode = "EQP_PARAM_UPDATE_FAILED";
                errorMessage = "eqp parameter 반영에 실패했습니다.";
            } else {
                status = UiTaskStatus.PASS;
                errorCode = null;
                errorMessage = null;
            }

            traceRegistry.handle(new UiCommandReply(
                    message.traceId(),
                    "TC-BUSINESS-APP",
                    message.eventType().name() + "_REP",
                    message.eqpId(),
                    message.interfaceType(),
                    status,
                    errorCode,
                    errorMessage
            ));
        };
    }

    private UiBusinessEventPublishPort businessPublisherForCompensationFailure(final DeployTraceRegistry traceRegistry) {
        final Map<String, Integer> eventCounts = new HashMap<>();
        return message -> {
            final String key = message.eqpId() + ":" + message.eventType().name();
            final int count = incrementEventCount(eventCounts, key);

            final UiTaskStatus status;
            final String errorCode;
            final String errorMessage;
            if (message.eventType() == UiCommandEventType.EQP_PARAMETER_UPDATE && count == 1) {
                status = UiTaskStatus.FAIL;
                errorCode = "EQP_PARAM_UPDATE_FAILED";
                errorMessage = "eqp parameter 반영에 실패했습니다.";
            } else if (message.eventType() == UiCommandEventType.EQP_MODEL_UPDATE && count >= 2) {
                status = UiTaskStatus.FAIL;
                errorCode = "PLUGIN_ROLLBACK_FAILED";
                errorMessage = "plugin rollback 에 실패했습니다.";
            } else {
                status = UiTaskStatus.PASS;
                errorCode = null;
                errorMessage = null;
            }

            traceRegistry.handle(new UiCommandReply(
                    message.traceId(),
                    "TC-BUSINESS-APP",
                    message.eventType().name() + "_REP",
                    message.eqpId(),
                    message.interfaceType(),
                    status,
                    errorCode,
                    errorMessage
            ));
        };
    }

    private DeployPlanCommand sendMessageCommand() {
        return new DeployPlanCommand(
                "PHOTO Send Message",
                UiCommandEventType.EQP_SEND_MESSAGE,
                DeployExecutionPolicy.BEST_EFFORT,
                new DeployPlanCommand.CommonRequest("SOCKET", sendMessageUiMessage(), null),
                List.of(new DeployPlanCommand.TargetRequest("PHOTO_001", objectMapper.createObjectNode()))
        );
    }

    private DeployPlanCommand businessJarUpdateCommand(final String... eqpIds) {
        final ObjectNode root = objectMapper.createObjectNode();
        final ObjectNode modelInfo = root.putObject("modelInfo");
        modelInfo.put("modelVersionKey", 1001L);
        modelInfo.put("modelKey", 500L);
        modelInfo.put("modelVersion", "v1");
        final ObjectNode businessJar = root.putObject("businessJar");
        businessJar.put("jarRef", 9002L);
        businessJar.put("jarFileName", "next-business.jar");
        businessJar.put("expectedSha256", "next-sha");
        businessJar.put("sourceType", "DB");

        final List<DeployPlanCommand.TargetRequest> targets = new ArrayList<>();
        for (String eqpId : eqpIds) {
            targets.add(new DeployPlanCommand.TargetRequest(eqpId, objectMapper.createObjectNode()));
        }

        return new DeployPlanCommand(
                "PHOTO Business Jar Update",
                UiCommandEventType.EQP_BUSINESS_JARFILE_UPDATE,
                DeployExecutionPolicy.BEST_EFFORT,
                new DeployPlanCommand.CommonRequest("SOCKET", root, null),
                List.copyOf(targets)
        );
    }

    private DeployPlanCommand eqpParamUpdateCommand(final String... eqpIds) {
        return eqpParamUpdateCommandWithVersion("EQP_PARAM_V1", eqpIds);
    }

    private DeployPlanCommand eqpParamUpdateCommandWithVersion(final String version, final String... eqpIds) {
        final ObjectNode root = objectMapper.createObjectNode();
        root.putObject("eqpParams").put("version", version);

        final List<DeployPlanCommand.TargetRequest> targets = new ArrayList<>();
        for (String eqpId : eqpIds) {
            targets.add(new DeployPlanCommand.TargetRequest(eqpId, objectMapper.createObjectNode()));
        }

        return new DeployPlanCommand(
                "PHOTO EQP Param Update",
                UiCommandEventType.EQP_REGISTER_DEPLOY,
                DeployExecutionPolicy.BEST_EFFORT,
                new DeployPlanCommand.CommonRequest("SOCKET", root, "REGISTER_EQP_PARAM_ONLY"),
                List.copyOf(targets)
        );
    }

    private DeployPlanCommand modelUpdateCommand(final String... eqpIds) {
        final ObjectNode root = objectMapper.createObjectNode();
        final ObjectNode modelInfo = root.putObject("modelInfo");
        modelInfo.put("modelVersionKey", 1002L);
        modelInfo.put("modelKey", 501L);
        modelInfo.put("modelVersion", "v2");

        final List<DeployPlanCommand.TargetRequest> targets = new ArrayList<>();
        for (String eqpId : eqpIds) {
            targets.add(new DeployPlanCommand.TargetRequest(eqpId, objectMapper.createObjectNode()));
        }

        return new DeployPlanCommand(
                "PHOTO Model Update",
                UiCommandEventType.EQP_REGISTER_DEPLOY,
                DeployExecutionPolicy.BEST_EFFORT,
                new DeployPlanCommand.CommonRequest("SOCKET", root, "REGISTER_MODEL_ONLY"),
                List.copyOf(targets)
        );
    }

    private DeployPlanCommand modelBusinessJarRegisterCommand(final String... eqpIds) {
        final ObjectNode root = objectMapper.createObjectNode();
        final ObjectNode modelInfo = root.putObject("modelInfo");
        modelInfo.put("modelVersionKey", 1002L);
        modelInfo.put("modelKey", 501L);
        modelInfo.put("modelVersion", "v2");
        final ObjectNode businessJar = root.putObject("businessJar");
        businessJar.put("jarRef", 9002L);
        businessJar.put("jarFileName", "next-business.jar");
        businessJar.put("expectedSha256", "next-sha");
        businessJar.put("sourceType", "DB");

        final List<DeployPlanCommand.TargetRequest> targets = new ArrayList<>();
        for (String eqpId : eqpIds) {
            targets.add(new DeployPlanCommand.TargetRequest(eqpId, objectMapper.createObjectNode()));
        }

        return new DeployPlanCommand(
                "PHOTO Model Business Jar Update",
                UiCommandEventType.EQP_REGISTER_DEPLOY,
                DeployExecutionPolicy.BEST_EFFORT,
                new DeployPlanCommand.CommonRequest("SOCKET", root, "REGISTER_MODEL_BUSINESS_JAR"),
                List.copyOf(targets)
        );
    }

    private DeployPlanCommand modelBusinessJarEqpParamRegisterCommand(final String... eqpIds) {
        final ObjectNode root = objectMapper.createObjectNode();
        final ObjectNode modelInfo = root.putObject("modelInfo");
        modelInfo.put("modelVersionKey", 1002L);
        modelInfo.put("modelKey", 501L);
        modelInfo.put("modelVersion", "v2");
        final ObjectNode businessJar = root.putObject("businessJar");
        businessJar.put("jarRef", 9002L);
        businessJar.put("jarFileName", "next-business.jar");
        businessJar.put("expectedSha256", "next-sha");
        businessJar.put("sourceType", "DB");
        root.putObject("eqpParams").put("version", "EQP_PARAM_V2");

        final List<DeployPlanCommand.TargetRequest> targets = new ArrayList<>();
        for (String eqpId : eqpIds) {
            targets.add(new DeployPlanCommand.TargetRequest(eqpId, objectMapper.createObjectNode()));
        }

        return new DeployPlanCommand(
                "PHOTO Model Business Jar Eqp Param Update",
                UiCommandEventType.EQP_REGISTER_DEPLOY,
                DeployExecutionPolicy.BEST_EFFORT,
                new DeployPlanCommand.CommonRequest("SOCKET", root, "REGISTER_MODEL_BUSINESS_JAR_EQP_PARAM"),
                List.copyOf(targets)
        );
    }

    private ObjectNode sendMessageUiMessage() {
        final ObjectNode root = objectMapper.createObjectNode();
        root.put("messageName", "EQP_CONDITION_CHECK");
        root.put("rawMessage", "CMD=CHECK_REPLY MATID=L-0121010 STATUS=PASS");
        return root;
    }

    private EqpManagementSnapshot snapshot(final String eqpId, final long eqpKey) {
        return snapshot(eqpId, eqpKey, "EQP_PARAM_V1", "EQP_PARAM_V1");
    }

    private EqpManagementSnapshot snapshot(
            final String eqpId,
            final long eqpKey,
            final String appliedParamVersion,
            final String payloadParamVersion
    ) {
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new EqpManagementSnapshot(
                new UiEqp(
                        eqpKey,
                        eqpId,
                        1L,
                        UiProtocolType.SOCKET,
                        "ACTIVE",
                        false,
                        1,
                        "10.10.10." + eqpKey,
                        6000,
                        1001L,
                        appliedParamVersion,
                        null,
                        null,
                        true,
                        now,
                        now,
                        "SYSTEM",
                        "SYSTEM"
                ),
                new UiModel(
                        1001L,
                        500L,
                        "PHOTO_MODEL",
                        null,
                        "v1",
                        UiProtocolType.SOCKET,
                        UiModelStatus.OPERATE,
                        null,
                        null,
                        now,
                        now,
                        "SYSTEM",
                        "SYSTEM"
                ),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(new UiEqpParam(
                        1L,
                        eqpKey,
                        "PPID",
                        payloadParamVersion,
                        "P1001",
                        null,
                        "SYSTEM",
                        now
                )),
                List.of(),
                null,
                new UiBusinessJar(
                        eqpKey,
                        "legacy-business.jar",
                        "legacy-business".getBytes(),
                        now,
                        now,
                        "SYSTEM",
                        "SYSTEM"
                )
        );
    }

    private com.nori.tc.ui.core.deploy.DeploySummaryView awaitTerminalSummary(final long deployKey) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            final var summary = deployService.getDeploySummary(deployKey);
            if (summary.terminal()) {
                return summary;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("terminal deploy summary를 기다리는 중 timeout이 발생했습니다. deployKey=" + deployKey);
    }

    private com.nori.tc.ui.core.deploy.DeploySummaryView awaitReadySummary(final long deployKey) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            final var summary = deployService.getDeploySummary(deployKey);
            if (summary.deployStatus() == DeployStatus.READY && !summary.pauseRequested()) {
                return summary;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("READY deploy summary를 기다리는 중 timeout이 발생했습니다. deployKey=" + deployKey);
    }

    private static int incrementEventCount(
            final Map<String, Integer> eventCounts,
            final String key
    ) {
        final Integer currentCount = eventCounts.get(key);
        final int nextCount = currentCount == null ? 1 : currentCount + 1;
        eventCounts.put(key, nextCount);
        return nextCount;
    }

    private UiDeployProperties deployProperties(final Path scenarioDirectory) {
        return new UiDeployProperties(
                scenarioDirectory.toString(),
                100,
                4,
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

    private static final class InMemoryEqpCrudPort implements EqpCrudPort {

        private final Map<String, EqpManagementSnapshot> snapshots = new LinkedHashMap<>();

        @Override
        public EqpManagementSnapshot create(final com.nori.tc.ui.core.eqp.EqpManagementCommand.Create command) {
            throw new UnsupportedOperationException("not used in test");
        }

        @Override
        public EqpManagementSnapshot update(
                final String eqpId,
                final com.nori.tc.ui.core.eqp.EqpManagementCommand.Update command
        ) {
            final EqpManagementSnapshot existing = snapshots.get(eqpId);
            if (existing == null) {
                throw new UiNotFoundException("설비를 찾을 수 없습니다. eqpId=" + eqpId);
            }

            final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            final EqpManagementSnapshot updated = new EqpManagementSnapshot(
                    new UiEqp(
                            existing.eqp().eqpKey(),
                            existing.eqp().eqpId(),
                            command.lineKey(),
                            existing.eqp().commInterface(),
                            command.commMode(),
                            command.isDev(),
                            command.routePartition(),
                            command.eqpIp(),
                            command.eqpPort(),
                            command.modelVersionKey(),
                            command.appliedParamVersion(),
                            command.gatewayJarRef(),
                            command.businessJarRef(),
                            existing.eqp().enabled(),
                            existing.eqp().createdAt(),
                            now,
                            existing.eqp().createdBy(),
                            command.actor()
                    ),
                    existing.model(),
                    existing.hsms(),
                    existing.socket(),
                    existing.plc(),
                    existing.logPolicy(),
                    existing.runtimeState(),
                    existing.connectionState(),
                    existing.portStatuses(),
                    existing.params(),
                    existing.paramVersionMetas(),
                    existing.gatewayJar(),
                    existing.businessJar()
            );
            snapshots.put(eqpId, updated);
            return updated;
        }

        @Override
        public void restore(final EqpManagementSnapshot snapshot) {
            snapshots.put(snapshot.eqp().eqpId(), snapshot);
        }

        @Override
        public void delete(final String eqpId) {
            snapshots.remove(eqpId);
        }

        @Override
        public Optional<EqpManagementSnapshot> findSnapshotByEqpId(final String eqpId) {
            return Optional.ofNullable(snapshots.get(eqpId));
        }

        private void put(final EqpManagementSnapshot snapshot) {
            snapshots.put(snapshot.eqp().eqpId(), snapshot);
        }
    }

    private static final class InMemoryDeployStorePort implements DeployStorePort {

        private long deploySequence = 0L;
        private long targetSequence = 0L;
        private long snapshotSequence = 0L;
        private final Map<Long, DeployRecord> deploys = new LinkedHashMap<>();
        private final Map<Long, List<DeployStoredSnapshot>> snapshotsByTargetKey = new LinkedHashMap<>();

        @Override
        public synchronized PersistedDeploy createDeploy(
                final DeployPlanCommand command,
                final DeployPlanningResult planningResult,
                final String actor
        ) {
            final long deployKey = ++deploySequence;
            final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            final List<PersistedDeployTarget> persistedTargets = new ArrayList<>();
            final List<TargetStateRecord> targetStates = new ArrayList<>();

            int targetOrder = 1;
            for (var targetPlan : planningResult.targets()) {
                final long targetKey = ++targetSequence;
                persistedTargets.add(new PersistedDeployTarget(targetKey, targetOrder, targetPlan));
                targetStates.add(new TargetStateRecord(
                        targetKey,
                        targetPlan.eqpId(),
                        targetPlan.noChange() ? DeployTargetStatus.NO_CHANGE : DeployTargetStatus.PENDING,
                        null,
                        null,
                        null,
                        null,
                        false,
                        null,
                        null,
                        null,
                        targetPlan.noChange() ? now : null,
                        now
                ));
                targetOrder++;
            }

            deploys.put(deployKey, new DeployRecord(
                    command,
                    command.deployName(),
                    command.eventType(),
                    DeployStatus.PLANNING,
                    command.executionPolicy(),
                    actor,
                    now,
                    command.commonRequest().interfaceType(),
                    optionalText(command.commonRequest().uiMessage(), "modelInfo", "modelName"),
                    optionalText(command.commonRequest().uiMessage(), "modelInfo", "modelVersion"),
                    optionalText(command.commonRequest().uiMessage(), "businessJar", "jarFileName"),
                    false,
                    null,
                    null,
                    null,
                    null,
                    persistedTargets,
                    targetStates
            ));

            return new PersistedDeploy(deployKey, DeployStatus.PLANNING, persistedTargets);
        }

        @Override
        public synchronized void updateDeployStatus(
                final long deployKey,
                final DeployStatus deployStatus,
                final boolean pauseRequested,
                final String lastErrorCode,
                final String lastErrorMessage
        ) {
            final DeployRecord deploy = requireDeploy(deployKey);
            deploy.deployStatus = deployStatus;
            deploy.pauseRequested = pauseRequested;
            if (deployStatus == DeployStatus.RUNNING && deploy.startedAt == null) {
                deploy.startedAt = OffsetDateTime.now(ZoneOffset.UTC);
            }
            if ((deployStatus == DeployStatus.COMPLETED
                    || deployStatus == DeployStatus.PARTIAL_FAILED
                    || deployStatus == DeployStatus.FAILED
                    || deployStatus == DeployStatus.CANCELED)
                    && deploy.finishedAt == null) {
                deploy.finishedAt = OffsetDateTime.now(ZoneOffset.UTC);
            }
            deploy.lastErrorCode = lastErrorCode == null ? deploy.lastErrorCode : lastErrorCode;
            deploy.lastErrorMessage = lastErrorMessage == null ? deploy.lastErrorMessage : lastErrorMessage;
        }

        @Override
        public synchronized void updatePauseRequested(final long deployKey, final boolean pauseRequested) {
            requireDeploy(deployKey).pauseRequested = pauseRequested;
        }

        @Override
        public synchronized void markTargetWaitingReply(
                final long deployTargetKey,
                final int stepOrder,
                final String stepCode,
                final int attemptNo,
                final String traceId,
                final OffsetDateTime stepStartedAt,
                final OffsetDateTime stepTimeoutAt
        ) {
            final TargetStateRecord state = requireTarget(deployTargetKey);
            state.targetStatus = DeployTargetStatus.RUNNING;
            state.currentStepOrder = stepOrder;
            state.currentStepCode = stepCode;
            state.currentStepStatus = DeployStepStatus.WAITING_REPLY;
            state.currentTraceId = traceId;
            state.startedAt = state.startedAt == null ? stepStartedAt : state.startedAt;
            state.finishedAt = null;
            state.updatedAt = stepStartedAt;
        }

        @Override
        public synchronized void markTargetCompleted(final long deployTargetKey, final OffsetDateTime finishedAt) {
            final TargetStateRecord state = requireTarget(deployTargetKey);
            state.targetStatus = DeployTargetStatus.COMPLETED;
            state.currentStepOrder = null;
            state.currentStepCode = null;
            state.currentStepStatus = null;
            state.currentTraceId = null;
            state.rollbackRequired = false;
            state.lastErrorCode = null;
            state.lastErrorMessage = null;
            state.finishedAt = finishedAt;
            state.updatedAt = finishedAt;
        }

        @Override
        public synchronized void markTargetRollingBack(
                final long deployTargetKey,
                final int stepOrder,
                final String stepCode,
                final int attemptNo,
                final String traceId,
                final String lastErrorCode,
                final String lastErrorMessage,
                final OffsetDateTime stepStartedAt,
                final OffsetDateTime stepTimeoutAt
        ) {
            final TargetStateRecord state = requireTarget(deployTargetKey);
            state.targetStatus = DeployTargetStatus.ROLLING_BACK;
            state.currentStepOrder = stepOrder;
            state.currentStepCode = stepCode;
            state.currentStepStatus = DeployStepStatus.ROLLING_BACK;
            state.currentTraceId = traceId;
            state.rollbackRequired = true;
            state.lastErrorCode = lastErrorCode;
            state.lastErrorMessage = lastErrorMessage;
            state.startedAt = state.startedAt == null ? stepStartedAt : state.startedAt;
            state.finishedAt = null;
            state.updatedAt = stepStartedAt;
        }

        @Override
        public synchronized void markTargetRolledBack(
                final long deployTargetKey,
                final String lastErrorCode,
                final String lastErrorMessage,
                final OffsetDateTime finishedAt
        ) {
            final TargetStateRecord state = requireTarget(deployTargetKey);
            state.targetStatus = DeployTargetStatus.ROLLED_BACK;
            state.currentStepOrder = null;
            state.currentStepCode = null;
            state.currentStepStatus = null;
            state.currentTraceId = null;
            state.rollbackRequired = false;
            state.lastErrorCode = lastErrorCode;
            state.lastErrorMessage = lastErrorMessage;
            state.finishedAt = finishedAt;
            state.updatedAt = finishedAt;
        }

        @Override
        public synchronized void markTargetFailed(
                final long deployTargetKey,
                final int stepOrder,
                final String stepCode,
                final DeployStepStatus stepStatus,
                final String lastErrorCode,
                final String lastErrorMessage,
                final boolean rollbackRequired,
                final OffsetDateTime finishedAt
        ) {
            final TargetStateRecord state = requireTarget(deployTargetKey);
            state.targetStatus = DeployTargetStatus.FAILED;
            state.currentStepOrder = stepOrder;
            state.currentStepCode = stepCode;
            state.currentStepStatus = stepStatus;
            state.currentTraceId = null;
            state.rollbackRequired = rollbackRequired;
            state.lastErrorCode = lastErrorCode;
            state.lastErrorMessage = lastErrorMessage;
            state.finishedAt = finishedAt;
            state.updatedAt = finishedAt;
        }

        @Override
        public synchronized void markTargetCompensationFailed(
                final long deployTargetKey,
                final int stepOrder,
                final String stepCode,
                final String lastErrorCode,
                final String lastErrorMessage,
                final OffsetDateTime finishedAt
        ) {
            final TargetStateRecord state = requireTarget(deployTargetKey);
            state.targetStatus = DeployTargetStatus.COMPENSATION_FAILED;
            state.currentStepOrder = stepOrder;
            state.currentStepCode = stepCode;
            state.currentStepStatus = DeployStepStatus.COMPENSATION_FAILED;
            state.currentTraceId = null;
            state.rollbackRequired = true;
            state.lastErrorCode = lastErrorCode;
            state.lastErrorMessage = lastErrorMessage;
            state.finishedAt = finishedAt;
            state.updatedAt = finishedAt;
        }

        @Override
        public synchronized void saveTargetSnapshot(
                final long deployTargetKey,
                final DeploySnapshotScope snapshotScope,
                final String stepCode,
                final DeploySnapshotKind snapshotKind,
                final String sourceOfTruth,
                final JsonNode snapshotData
        ) {
            final DeployStoredSnapshot snapshot = new DeployStoredSnapshot(
                    ++snapshotSequence,
                    deployTargetKey,
                    snapshotScope,
                    stepCode,
                    snapshotKind,
                    sourceOfTruth,
                    snapshotData.deepCopy(),
                    OffsetDateTime.now(ZoneOffset.UTC)
            );
            snapshotsByTargetKey.computeIfAbsent(deployTargetKey, ignored -> new ArrayList<>()).add(snapshot);
        }

        @Override
        public synchronized Optional<DeployStoredSnapshot> findLatestTargetSnapshot(
                final long deployTargetKey,
                final DeploySnapshotScope snapshotScope,
                final String stepCode,
                final DeploySnapshotKind snapshotKind
        ) {
            return snapshotsByTargetKey.getOrDefault(deployTargetKey, List.of()).stream()
                    .filter(snapshot -> snapshot.snapshotScope() == snapshotScope)
                    .filter(snapshot -> snapshot.snapshotKind() == snapshotKind)
                    .filter(snapshot -> Objects.equals(snapshot.stepCode(), stepCode))
                    .reduce((first, second) -> second);
        }

        @Override
        public synchronized Optional<DeploySummarySnapshot> findDeploySummary(final long deployKey) {
            final DeployRecord deploy = deploys.get(deployKey);
            if (deploy == null) {
                return Optional.empty();
            }
            final OffsetDateTime startedAt = deploy.targets.stream()
                    .map(target -> target.startedAt)
                    .filter(Objects::nonNull)
                    .min(OffsetDateTime::compareTo)
                    .orElse(null);
            final OffsetDateTime finishedAt = isTerminal(deploy.deployStatus)
                    ? deploy.targets.stream()
                    .map(target -> target.finishedAt)
                    .filter(Objects::nonNull)
                    .max(OffsetDateTime::compareTo)
                    .orElse(null)
                    : null;
            return Optional.of(new DeploySummarySnapshot(
                    deployKey,
                    deploy.deployName,
                    deploy.eventType,
                    null,
                    deploy.deployStatus,
                    deploy.pauseRequested,
                    deploy.executionPolicy,
                    deploy.createdAt,
                    startedAt,
                    finishedAt,
                    summarizeTargets(deployKey),
                    deploy.lastErrorCode,
                    deploy.lastErrorMessage
            ));
        }

        @Override
        public synchronized Optional<DeployExecutionPlan> findDeployExecutionPlan(final long deployKey) {
            final DeployRecord deploy = deploys.get(deployKey);
            if (deploy == null) {
                return Optional.empty();
            }
            final List<DeployExecutionTarget> targets = deploy.persistedTargets.stream()
                    .map(target -> {
                        final TargetStateRecord state = requireTarget(target.deployTargetKey());
                        return new DeployExecutionTarget(
                                target,
                                state.targetStatus,
                                state.currentStepOrder,
                                state.currentStepCode,
                                state.currentStepStatus
                        );
                    })
                    .toList();
            return Optional.of(new DeployExecutionPlan(deployKey, deploy.eventType, targets));
        }

        @Override
        public synchronized Optional<DeployActiveEqpConflict> findActiveEqpConflict(
                final java.util.Collection<String> eqpIds,
                final Long excludedDeployKey
        ) {
            if (eqpIds == null) {
                return Optional.empty();
            }
            return eqpIds.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(eqpId -> !eqpId.isEmpty())
                    .filter(eqpId -> deploys.entrySet().stream()
                            .filter(entry -> !Objects.equals(entry.getKey(), excludedDeployKey))
                            .filter(entry -> entry.getValue().deployStatus == DeployStatus.READY
                                    || entry.getValue().deployStatus == DeployStatus.RUNNING)
                            .flatMap(entry -> entry.getValue().persistedTargets.stream()
                                    .map(target -> new DeployActiveEqpConflict(
                                            target.planDraft().eqpId(),
                                            entry.getKey(),
                                            entry.getValue().deployStatus,
                                            entry.getValue().deployName,
                                            entry.getValue().requestedBy
                                    )))
                            .anyMatch(conflict -> conflict.eqpId().equals(eqpId)))
                    .findFirst()
                    .flatMap(eqpId -> deploys.entrySet().stream()
                            .filter(entry -> !Objects.equals(entry.getKey(), excludedDeployKey))
                            .filter(entry -> entry.getValue().deployStatus == DeployStatus.READY
                                    || entry.getValue().deployStatus == DeployStatus.RUNNING)
                            .flatMap(entry -> entry.getValue().persistedTargets.stream()
                                    .filter(target -> target.planDraft().eqpId().equals(eqpId))
                                    .map(target -> new DeployActiveEqpConflict(
                                            target.planDraft().eqpId(),
                                            entry.getKey(),
                                            entry.getValue().deployStatus,
                                            entry.getValue().deployName,
                                            entry.getValue().requestedBy
                                    )))
                            .findFirst());
        }

        @Override
        public synchronized Optional<DeployPlanCommand> findRedeployCommand(final long deployKey) {
            final DeployRecord deploy = deploys.get(deployKey);
            if (deploy == null) {
                return Optional.empty();
            }
            return Optional.of(deploy.originalCommand);
        }

        @Override
        public synchronized List<String> findActiveTraceIds(final long deployKey) {
            final DeployRecord deploy = requireDeploy(deployKey);
            return deploy.targets.stream()
                    .map(target -> target.currentTraceId)
                    .filter(Objects::nonNull)
                    .toList();
        }

        @Override
        public synchronized void markTargetsCanceled(
                final long deployKey,
                final OffsetDateTime finishedAt,
                final String lastErrorCode,
                final String lastErrorMessage
        ) {
            final DeployRecord deploy = requireDeploy(deployKey);
            for (TargetStateRecord target : deploy.targets) {
                if (target.targetStatus == DeployTargetStatus.PENDING
                        || target.targetStatus == DeployTargetStatus.RUNNING
                        || target.targetStatus == DeployTargetStatus.ROLLING_BACK) {
                    target.targetStatus = DeployTargetStatus.CANCELED;
                    target.currentStepStatus = DeployStepStatus.CANCELED;
                    target.currentTraceId = null;
                    target.lastErrorCode = lastErrorCode;
                    target.lastErrorMessage = lastErrorMessage;
                    target.finishedAt = finishedAt;
                    target.updatedAt = finishedAt;
                }
            }
        }

        @Override
        public synchronized void markTargetReadyForResume(
                final long deployTargetKey,
                final Integer completedStepOrder,
                final String completedStepCode,
                final OffsetDateTime updatedAt
        ) {
            final TargetStateRecord state = requireTarget(deployTargetKey);
            state.targetStatus = DeployTargetStatus.PENDING;
            state.currentStepOrder = completedStepOrder;
            state.currentStepCode = completedStepCode;
            state.currentStepStatus = completedStepOrder == null ? null : DeployStepStatus.COMPLETED;
            state.currentTraceId = null;
            state.finishedAt = null;
            state.updatedAt = updatedAt;
        }

        @Override
        public synchronized boolean deleteDeploy(final long deployKey) {
            return deploys.remove(deployKey) != null;
        }

        @Override
        public synchronized PagedResponse<DeployHistoryView> findDeployHistory(
                final String modelName,
                final String requestedBy,
                final OffsetDateTime fromCreatedAt,
                final OffsetDateTime toCreatedAtExclusive,
                final boolean terminalOnly,
                final UiPageRequest pageRequest
        ) {
            final List<Map.Entry<Long, DeployRecord>> filteredEntries = deploys.entrySet().stream()
                    .filter(entry -> {
                        final DeployRecord deploy = entry.getValue();
                        final boolean isTerminal = deploy.deployStatus == DeployStatus.COMPLETED
                                || deploy.deployStatus == DeployStatus.PARTIAL_FAILED
                                || deploy.deployStatus == DeployStatus.FAILED
                                || deploy.deployStatus == DeployStatus.CANCELED;
                        if (terminalOnly) {
                            if (!isTerminal) {
                                return false;
                            }
                        } else if (isTerminal) {
                            return false;
                        }
                        if (modelName != null && !modelName.isBlank()) {
                            final String normalizedModelName = modelName.toLowerCase();
                            final boolean matchesDeployName = deploy.deployName.toLowerCase().contains(normalizedModelName);
                            final boolean matchesSelectedModel = deploy.selectedModelName != null
                                    && deploy.selectedModelName.toLowerCase().contains(normalizedModelName);
                            if (!matchesDeployName && !matchesSelectedModel) {
                                return false;
                            }
                        }
                        if (requestedBy != null && !requestedBy.isBlank()
                                && !deploy.requestedBy.toLowerCase().contains(requestedBy.toLowerCase())) {
                            return false;
                        }
                        if (fromCreatedAt != null && deploy.createdAt.isBefore(fromCreatedAt)) {
                            return false;
                        }
                        return toCreatedAtExclusive == null || deploy.createdAt.isBefore(toCreatedAtExclusive);
                    })
                    .sorted((left, right) -> {
                        final int createdAtCompare = right.getValue().createdAt.compareTo(left.getValue().createdAt);
                        if (createdAtCompare != 0) {
                            return createdAtCompare;
                        }
                        return Long.compare(right.getKey(), left.getKey());
                    })
                    .toList();

            final int offset = pageRequest.offset();
            final int end = Math.min(filteredEntries.size(), offset + pageRequest.limit());
            final List<DeployHistoryView> items = offset >= filteredEntries.size()
                    ? List.of()
                    : filteredEntries.subList(offset, end).stream()
                    .map(entry -> {
                        final long deployKey = entry.getKey();
                        final DeployRecord deploy = entry.getValue();
                        return new DeployHistoryView(
                                deployKey,
                                deploy.deployName,
                                deploy.eventType,
                                deploy.deployStatus,
                                deploy.pauseRequested,
                                deploy.executionPolicy,
                                deploy.requestedBy,
                                deploy.createdAt,
                                startedAt(deploy),
                                finishedAt(deploy),
                                deploy.deployStatus == DeployStatus.COMPLETED
                                        || deploy.deployStatus == DeployStatus.PARTIAL_FAILED
                                        || deploy.deployStatus == DeployStatus.FAILED
                                        || deploy.deployStatus == DeployStatus.CANCELED,
                                deploy.interfaceType,
                                deploy.selectedModelName,
                                deploy.selectedModelVersion,
                                deploy.selectedBusinessJarName,
                                summarizeTargets(deployKey)
                        );
                    })
                    .toList();

            return PagedResponse.of(items, offset, pageRequest.limit(), filteredEntries.size());
        }

        @Override
        public synchronized DeployTargetCounts summarizeTargets(final long deployKey) {
            final DeployRecord deploy = requireDeploy(deployKey);
            int pending = 0;
            int noChange = 0;
            int running = 0;
            int completed = 0;
            int failed = 0;
            int rollingBack = 0;
            int rolledBack = 0;
            int compensationFailed = 0;
            int canceled = 0;

            for (TargetStateRecord target : deploy.targets) {
                switch (target.targetStatus) {
                    case PENDING -> pending++;
                    case NO_CHANGE -> noChange++;
                    case RUNNING -> running++;
                    case COMPLETED -> completed++;
                    case FAILED -> failed++;
                    case ROLLING_BACK -> rollingBack++;
                    case ROLLED_BACK -> rolledBack++;
                    case COMPENSATION_FAILED -> compensationFailed++;
                    case CANCELED -> canceled++;
                }
            }

            return new DeployTargetCounts(
                    deploy.targets.size(),
                    pending,
                    noChange,
                    running,
                    completed,
                    failed,
                    rollingBack,
                    rolledBack,
                    compensationFailed,
                    canceled
            );
        }

        @Override
        public synchronized DeployTargetPage findDeployTargets(
                final long deployKey,
                final int page,
                final int size,
                final DeployTargetStatus statusFilter,
                final String eqpIdFilter
        ) {
            final DeployRecord deploy = requireDeploy(deployKey);
            final List<DeployTargetSnapshot> filtered = deploy.targets.stream()
                    .filter(target -> statusFilter == null || target.targetStatus == statusFilter)
                    .filter(target -> eqpIdFilter == null || target.eqpId.contains(eqpIdFilter))
                    .map(target -> new DeployTargetSnapshot(
                            target.deployTargetKey,
                            target.eqpId,
                            target.targetStatus,
                            target.currentStepOrder,
                            target.currentStepCode,
                            target.currentStepStatus,
                            target.rollbackRequired,
                            target.lastErrorCode,
                            target.lastErrorMessage,
                            target.updatedAt
                    ))
                    .toList();

            final int offset = page * size;
            final int end = Math.min(filtered.size(), offset + size);
            final List<DeployTargetSnapshot> pageItems = offset >= filtered.size()
                    ? List.of()
                    : filtered.subList(offset, end);
            final int totalPages = filtered.isEmpty() ? 0 : (int) Math.ceil((double) filtered.size() / size);

            return new DeployTargetPage(
                    deployKey,
                    page,
                    size,
                    filtered.size(),
                    totalPages,
                    pageItems
            );
        }

        private DeployRecord requireDeploy(final long deployKey) {
            final DeployRecord deploy = deploys.get(deployKey);
            if (deploy == null) {
                throw new IllegalArgumentException("deploy not found: " + deployKey);
            }
            return deploy;
        }

        private TargetStateRecord requireTarget(final long deployTargetKey) {
            return deploys.values().stream()
                    .flatMap(deploy -> deploy.targets.stream())
                    .filter(target -> target.deployTargetKey == deployTargetKey)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("target not found: " + deployTargetKey));
        }

        private synchronized List<DeployStoredSnapshot> findSnapshots(
                final long deployTargetKey,
                final DeploySnapshotScope snapshotScope
        ) {
            return snapshotsByTargetKey.getOrDefault(deployTargetKey, List.of()).stream()
                    .filter(snapshot -> snapshot.snapshotScope() == snapshotScope)
                    .toList();
        }

        private DeployExecutionSeed executionSeed(final long deployKey) {
            final DeployRecord deploy = requireDeploy(deployKey);
            final List<DeployExecutionTarget> executableTargets = deploy.persistedTargets.stream()
                    .filter(target -> !target.planDraft().noChange())
                    .map(target -> {
                        final TargetStateRecord state = requireTarget(target.deployTargetKey());
                        return new DeployExecutionTarget(
                                target,
                                state.targetStatus,
                                state.currentStepOrder,
                                state.currentStepCode,
                                state.currentStepStatus
                        );
                    })
                    .toList();
            return new DeployExecutionSeed(deploy.eventType, executableTargets);
        }

        private OffsetDateTime startedAt(final DeployRecord deploy) {
            return deploy.targets.stream()
                    .map(target -> target.startedAt)
                    .filter(Objects::nonNull)
                    .min(OffsetDateTime::compareTo)
                    .orElse(null);
        }

        private OffsetDateTime finishedAt(final DeployRecord deploy) {
            if (!isTerminal(deploy.deployStatus)) {
                return null;
            }
            return deploy.targets.stream()
                    .map(target -> target.finishedAt)
                    .filter(Objects::nonNull)
                    .max(OffsetDateTime::compareTo)
                    .orElse(null);
        }

        private boolean isTerminal(final DeployStatus deployStatus) {
            return deployStatus == DeployStatus.COMPLETED
                    || deployStatus == DeployStatus.PARTIAL_FAILED
                    || deployStatus == DeployStatus.FAILED
                    || deployStatus == DeployStatus.CANCELED;
        }

        private static String optionalText(
                final JsonNode root,
                final String firstField,
                final String secondField
        ) {
            if (root == null) {
                return null;
            }
            final JsonNode firstNode = root.path(firstField);
            if (firstNode.isMissingNode() || firstNode.isNull()) {
                return null;
            }
            final JsonNode targetNode = firstNode.path(secondField);
            if (targetNode.isMissingNode() || targetNode.isNull()) {
                return null;
            }
            final String value = targetNode.asString(null);
            return value == null || value.isBlank() ? null : value;
        }

        private static final class DeployRecord {
            private final DeployPlanCommand originalCommand;
            private final String deployName;
            private final UiCommandEventType eventType;
            private final DeployExecutionPolicy executionPolicy;
            private final String requestedBy;
            private final OffsetDateTime createdAt;
            private final String interfaceType;
            private final String selectedModelName;
            private final String selectedModelVersion;
            private final String selectedBusinessJarName;
            private final List<PersistedDeployTarget> persistedTargets;
            private final List<TargetStateRecord> targets;
            private DeployStatus deployStatus;
            private boolean pauseRequested;
            private OffsetDateTime startedAt;
            private OffsetDateTime finishedAt;
            private String lastErrorCode;
            private String lastErrorMessage;

            private DeployRecord(
                    final DeployPlanCommand originalCommand,
                    final String deployName,
                    final UiCommandEventType eventType,
                    final DeployStatus deployStatus,
                    final DeployExecutionPolicy executionPolicy,
                    final String requestedBy,
                    final OffsetDateTime createdAt,
                    final String interfaceType,
                    final String selectedModelName,
                    final String selectedModelVersion,
                    final String selectedBusinessJarName,
                    final boolean pauseRequested,
                    final OffsetDateTime startedAt,
                    final OffsetDateTime finishedAt,
                    final String lastErrorCode,
                    final String lastErrorMessage,
                    final List<PersistedDeployTarget> persistedTargets,
                    final List<TargetStateRecord> targets
            ) {
                this.originalCommand = originalCommand;
                this.deployName = deployName;
                this.eventType = eventType;
                this.deployStatus = deployStatus;
                this.executionPolicy = executionPolicy;
                this.requestedBy = requestedBy;
                this.createdAt = createdAt;
                this.interfaceType = interfaceType;
                this.selectedModelName = selectedModelName;
                this.selectedModelVersion = selectedModelVersion;
                this.selectedBusinessJarName = selectedBusinessJarName;
                this.pauseRequested = pauseRequested;
                this.startedAt = startedAt;
                this.finishedAt = finishedAt;
                this.lastErrorCode = lastErrorCode;
                this.lastErrorMessage = lastErrorMessage;
                this.persistedTargets = persistedTargets;
                this.targets = targets;
            }
        }

        private static final class TargetStateRecord {
            private final long deployTargetKey;
            private final String eqpId;
            private DeployTargetStatus targetStatus;
            private Integer currentStepOrder;
            private String currentStepCode;
            private DeployStepStatus currentStepStatus;
            private String currentTraceId;
            private boolean rollbackRequired;
            private String lastErrorCode;
            private String lastErrorMessage;
            private OffsetDateTime startedAt;
            private OffsetDateTime finishedAt;
            private OffsetDateTime updatedAt;

            private TargetStateRecord(
                    final long deployTargetKey,
                    final String eqpId,
                    final DeployTargetStatus targetStatus,
                    final Integer currentStepOrder,
                    final String currentStepCode,
                    final DeployStepStatus currentStepStatus,
                    final String currentTraceId,
                    final boolean rollbackRequired,
                    final String lastErrorCode,
                    final String lastErrorMessage,
                    final OffsetDateTime startedAt,
                    final OffsetDateTime finishedAt,
                    final OffsetDateTime updatedAt
            ) {
                this.deployTargetKey = deployTargetKey;
                this.eqpId = eqpId;
                this.targetStatus = targetStatus;
                this.currentStepOrder = currentStepOrder;
                this.currentStepCode = currentStepCode;
                this.currentStepStatus = currentStepStatus;
                this.currentTraceId = currentTraceId;
                this.rollbackRequired = rollbackRequired;
                this.lastErrorCode = lastErrorCode;
                this.lastErrorMessage = lastErrorMessage;
                this.startedAt = startedAt;
                this.finishedAt = finishedAt;
                this.updatedAt = updatedAt;
            }
        }

        private record DeployExecutionSeed(
                UiCommandEventType eventType,
                List<DeployExecutionTarget> executableTargets
        ) {
        }
    }
}
