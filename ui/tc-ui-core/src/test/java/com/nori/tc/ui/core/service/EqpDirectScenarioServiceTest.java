package com.nori.tc.ui.core.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.nori.tc.ui.core.deploy.DeployCurrentState;
import com.nori.tc.ui.core.deploy.DeployScenarioCatalogMeta;
import com.nori.tc.ui.core.eqp.EqpCommandResult;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.eqp.EqpRepairTask;
import com.nori.tc.ui.core.model.UiCommandMessage;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.model.UiEquipmentProfileSnapshot;
import com.nori.tc.ui.core.port.db.DemoJobCleanupPort;
import com.nori.tc.ui.core.port.db.DeployScenarioCatalogMetaPort;
import com.nori.tc.ui.core.port.db.DeployStorePort;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.core.port.db.EqpRepairTaskPort;
import com.nori.tc.ui.core.port.db.ModelCrudPort;
import com.nori.tc.ui.core.port.db.ModelDetailQueryPort;
import com.nori.tc.ui.core.port.redis.DeployTraceRedisPort;
import com.nori.tc.ui.core.properties.UiDeployProperties;
import com.nori.tc.ui.core.registry.DeployTraceRedisLookupResult;
import com.nori.tc.ui.domain.common.eqp.UiLogLevel;
import com.nori.tc.ui.domain.common.model.UiModelStatus;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpParam;
import com.nori.tc.ui.domain.eqp.UiEqpSocket;
import com.nori.tc.ui.domain.jar.UiBusinessJar;
import com.nori.tc.ui.domain.jar.UiGatewayJar;
import com.nori.tc.ui.domain.model.UiModel;
import com.nori.tc.ui.domain.model.UiModelParam;
import com.nori.tc.ui.domain.task.UiTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EqpDirectScenarioServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private InMemoryEqpCrudPort eqpCrudPort;
    private InMemoryRepairTaskPort repairTaskPort;
    private InMemoryDeployTraceRedisPort traceRedisPort;
    private DeployTraceRegistry traceRegistry;
    private RecordingPublisher gatewayPublisher;
    private RecordingPublisher businessPublisher;
    private EqpDirectScenarioService service;
    private List<String> operationLog;

    @BeforeEach
    void setUp() {
        eqpCrudPort = new InMemoryEqpCrudPort();
        repairTaskPort = new InMemoryRepairTaskPort();
        traceRedisPort = new InMemoryDeployTraceRedisPort();
        traceRegistry = new DeployTraceRegistry(traceRedisPort);
        operationLog = new ArrayList<>();
        eqpCrudPort.operationLog = operationLog;
        gatewayPublisher = new RecordingPublisher("TC-GATEWAY-APP", traceRegistry, operationLog);
        businessPublisher = new RecordingPublisher("TC-BUSINESS-APP", traceRegistry, operationLog);
        service = newService();
    }

    @Test
    void createRemoteFailureRollsBackLocalCreate() {
        businessPublisher.behavior = (message, source) -> {
            if (message.eventType().name().equals("EQP_CREATE")) {
                businessPublisher.replyFail(message, "MODEL_RUNTIME_FAILED", "business runtime 적재 실패");
                return;
            }
            businessPublisher.replyPass(message);
        };

        final EqpCommandResult result = service.executeCreate(createCommand("PHOTO_NEW"));

        assertFalse(result.success());
        assertEquals(500L, gatewayPublisher.messages.getFirst().equipmentProfile().modelKey());
        assertTrue(result.errorCode() != null && !result.errorCode().isBlank());
        assertTrue(eqpCrudPort.findSnapshotByEqpId("PHOTO_NEW").isEmpty());
    }

    @Test
    void updateMidStepFailureRestoresPreviousSnapshot() {
        eqpCrudPort.put(snapshot("PHOTO_001", 1L, "10.10.10.1", 6000, "EQP_PARAM_V1", 3001L, 4001L));
        gatewayPublisher.behavior = (message, source) -> {
            if (message.eventType().name().equals("EQP_UPDATE") && gatewayPublisher.countByEvent("EQP_UPDATE") == 1) {
                gatewayPublisher.replyFail(message, "GATEWAY_CONTEXT_FAILED", "gateway context 반영 실패");
                return;
            }
            gatewayPublisher.replyPass(message);
        };

        final EqpCommandResult result = service.executeUpdate("PHOTO_001", updateCommand("10.10.10.9", 6100, null, null, null));

        assertFalse(result.success());
        assertEquals(500L, gatewayPublisher.messages.getFirst().equipmentProfile().modelKey());
        final EqpManagementSnapshot restored = eqpCrudPort.findSnapshotByEqpId("PHOTO_001").orElseThrow();
        assertEquals("10.10.10.1", restored.eqp().eqpIp());
        assertEquals(6000, restored.eqp().eqpPort());
        assertEquals("EQP_PARAM_V1", restored.eqp().appliedParamVersion());
        assertEquals(3001L, restored.eqp().gatewayJarKey());
        assertEquals(4001L, restored.eqp().businessJarKey());
    }

    @Test
    void deleteRunsRemoteStepsBeforeDbDelete() {
        eqpCrudPort.put(snapshot("PHOTO_001", 1L, "10.10.10.1", 6000, "EQP_PARAM_V1", 3001L, 4001L));

        final EqpCommandResult result = service.executeDelete("PHOTO_001");

        assertTrue(result.success());
        assertTrue(eqpCrudPort.findSnapshotByEqpId("PHOTO_001").isEmpty());
        assertEquals(
                List.of(
                        "publish:TC-GATEWAY-APP:EQP_END",
                        "publish:TC-GATEWAY-APP:EQP_DELETE",
                        "publish:TC-BUSINESS-APP:EQP_DELETE",
                        "db-delete:PHOTO_001"
                ),
                operationLog
        );
    }

    @Test
    void startDoesNotCompleteBeforeReplySuccess() throws Exception {
        eqpCrudPort.put(snapshot("PHOTO_001", 1L, "10.10.10.1", 6000, "EQP_PARAM_V1", 3001L, 4001L));
        final CountDownLatch publishStarted = new CountDownLatch(1);
        final CountDownLatch releaseReply = new CountDownLatch(1);
        gatewayPublisher.behavior = (message, source) -> {
            publishStarted.countDown();
            await(releaseReply);
            gatewayPublisher.replyPass(message);
        };

        final CompletableFuture<EqpCommandResult> future = CompletableFuture.supplyAsync(
                () -> service.executeStart("PHOTO_001", "SOCKET", reasonUiMessage("USER_REQUEST"), "tester")
        );

        assertTrue(publishStarted.await(1, TimeUnit.SECONDS));
        assertFalse(future.isDone());
        releaseReply.countDown();

        final EqpCommandResult result = future.get(1, TimeUnit.SECONDS);
        assertTrue(result.success());
    }

    @Test
    void sendMessageCompletesBySingleReplyResult() {
        eqpCrudPort.put(snapshot("PHOTO_001", 1L, "10.10.10.1", 6000, "EQP_PARAM_V1", 3001L, 4001L));
        gatewayPublisher.behavior = (message, source) ->
                gatewayPublisher.replyFail(message, "SEND_MESSAGE_FAILED", "메시지 송신 실패");

        final EqpCommandResult result = service.executeSendMessage(
                "PHOTO_001",
                "SOCKET",
                sendMessageUiMessage(),
                "tester"
        );

        assertFalse(result.success());
        assertEquals("SEND_MESSAGE_FAILED", result.errorCode());
        assertEquals(1, gatewayPublisher.messages.size());
    }

    @Test
    void gatewayJarUpdateChangesLocalStateOnlyAfterReplySuccess() throws Exception {
        eqpCrudPort.put(snapshot("PHOTO_001", 1L, "10.10.10.1", 6000, "EQP_PARAM_V1", 3001L, 4001L));
        final CountDownLatch publishStarted = new CountDownLatch(1);
        final CountDownLatch releaseReply = new CountDownLatch(1);
        gatewayPublisher.behavior = (message, source) -> {
            publishStarted.countDown();
            await(releaseReply);
            gatewayPublisher.replyPass(message);
        };

        final CompletableFuture<EqpCommandResult> future = CompletableFuture.supplyAsync(
                () -> service.executeGatewayJarUpdate("PHOTO_001", 3002L, "tester")
        );

        assertTrue(publishStarted.await(1, TimeUnit.SECONDS));
        assertEquals(3001L, eqpCrudPort.findSnapshotByEqpId("PHOTO_001").orElseThrow().eqp().gatewayJarKey());
        releaseReply.countDown();

        assertTrue(future.get(1, TimeUnit.SECONDS).success());
        assertEquals(3002L, eqpCrudPort.findSnapshotByEqpId("PHOTO_001").orElseThrow().eqp().gatewayJarKey());
    }

    @Test
    void businessJarUpdateChangesLocalStateOnlyAfterReplySuccess() throws Exception {
        eqpCrudPort.put(snapshot("PHOTO_001", 1L, "10.10.10.1", 6000, "EQP_PARAM_V1", 3001L, 4001L));
        final CountDownLatch publishStarted = new CountDownLatch(1);
        final CountDownLatch releaseReply = new CountDownLatch(1);
        businessPublisher.behavior = (message, source) -> {
            publishStarted.countDown();
            await(releaseReply);
            businessPublisher.replyPass(message);
        };

        final CompletableFuture<EqpCommandResult> future = CompletableFuture.supplyAsync(
                () -> service.executeBusinessJarUpdate("PHOTO_001", 4002L, "tester")
        );

        assertTrue(publishStarted.await(1, TimeUnit.SECONDS));
        assertEquals(4001L, eqpCrudPort.findSnapshotByEqpId("PHOTO_001").orElseThrow().eqp().businessJarKey());
        releaseReply.countDown();

        assertTrue(future.get(1, TimeUnit.SECONDS).success());
        assertEquals(4002L, eqpCrudPort.findSnapshotByEqpId("PHOTO_001").orElseThrow().eqp().businessJarKey());
    }

    @Test
    void reconnectCompletesBySingleReplyResult() {
        eqpCrudPort.put(snapshot("PHOTO_001", 1L, "10.10.10.1", 6000, "EQP_PARAM_V1", 3001L, 4001L));

        final EqpCommandResult result = service.executeReconnect(
                "PHOTO_001",
                "SOCKET",
                reasonUiMessage("USER_REQUEST"),
                "tester"
        );

        assertTrue(result.success());
        assertEquals(1, gatewayPublisher.messages.size());
    }

    @Test
    void passiveLifecycleCommandsAreRejectedBeforePublish() {
        eqpCrudPort.put(snapshot("PASSIVE_001", 2L, "0.0.0.0", 7000, "EQP_PARAM_V1", 3001L, 4001L, "PASSIVE"));

        final EqpCommandResult startResult = service.executeStart(
                "PASSIVE_001",
                "SOCKET",
                reasonUiMessage("USER_REQUEST"),
                "tester"
        );
        final EqpCommandResult endResult = service.executeEnd(
                "PASSIVE_001",
                "SOCKET",
                reasonUiMessage("USER_REQUEST"),
                "tester"
        );
        final EqpCommandResult reconnectResult = service.executeReconnect(
                "PASSIVE_001",
                "SOCKET",
                reasonUiMessage("USER_REQUEST"),
                "tester"
        );

        assertFalse(startResult.success());
        assertFalse(endResult.success());
        assertFalse(reconnectResult.success());
        assertEquals(400, startResult.statusCode());
        assertEquals(400, endResult.statusCode());
        assertEquals(400, reconnectResult.statusCode());
        assertTrue(gatewayPublisher.messages.isEmpty());
    }

    private EqpDirectScenarioService newService() {
        final UiDeployProperties deployProperties = new UiDeployProperties(
                "/home/donggeon/workspace_nori/nori-tc-ui-backend/ui/tc-ui-core/src/main/resources/deploy-scenarios",
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
        final DeployScenarioCatalogService catalogService = new DeployScenarioCatalogService(
                deployProperties,
                new InMemoryCatalogMetaPort(),
                objectMapper
        );
        catalogService.initializeCatalog();
        final DeployPlanningService planningService = new DeployPlanningService(
                deployProperties,
                eqpId -> eqpCrudPort.findSnapshotByEqpId(eqpId).map(this::toCurrentState),
                catalogService,
                objectMapper
        );
        final ModelCrudPort modelCrudPort = mock(ModelCrudPort.class);
        when(modelCrudPort.findByModelVersionKey(1001L)).thenReturn(Optional.of(model(1001L, "v1")));
        when(modelCrudPort.findByModelVersionKey(1002L)).thenReturn(Optional.of(model(1002L, "v2")));
        final ModelDetailQueryPort modelDetailQueryPort = mock(ModelDetailQueryPort.class);
        when(modelDetailQueryPort.findParamsByModelVersionKey(1001L)).thenReturn(List.of(
                new UiModelParam(1L, 1001L, "M_TEMP", "900", null, OffsetDateTime.now(ZoneOffset.UTC))
        ));
        when(modelDetailQueryPort.findParamsByModelVersionKey(1002L)).thenReturn(List.of(
                new UiModelParam(2L, 1002L, "M_TEMP", "901", null, OffsetDateTime.now(ZoneOffset.UTC))
        ));

        final DeployServiceCommandSupport commandSupport = new DeployServiceCommandSupport(
                mock(DeployStorePort.class),
                eqpCrudPort,
                eqpQueryPort(),
                modelCrudPort,
                modelDetailQueryPort,
                gatewayPublisher,
                businessPublisher,
                objectMapper
        );
        final DeployCommandAssembler commandAssembler = new DeployCommandAssembler(commandSupport);
        final EqpRepairTaskService repairTaskService = new EqpRepairTaskService(repairTaskPort);
        final EqpPersistenceService persistenceService = new EqpPersistenceService(eqpCrudPort);
        final EqpManagementCommandValidator validator = new EqpManagementCommandValidator(modelCrudPort);
        final EqpDirectScenarioLocalMutationCoordinator localMutationCoordinator =
                new EqpDirectScenarioLocalMutationCoordinator(persistenceService, eqpCrudPort, modelDetailQueryPort, objectMapper);
        final DeployTraceRecorder traceRecorder = new DeployTraceRecorder(traceRegistry);
        final EqpDirectScenarioRollbackService rollbackService = new EqpDirectScenarioRollbackService(
                commandAssembler,
                traceRecorder,
                repairTaskService,
                localMutationCoordinator,
                eqpCrudPort,
                modelDetailQueryPort,
                objectMapper
        );
        final EqpDirectScenarioRunner runner = new EqpDirectScenarioRunner(
                commandAssembler,
                traceRecorder,
                localMutationCoordinator,
                rollbackService
        );
        return new EqpDirectScenarioService(
                persistenceService,
                validator,
                new EqpDirectScenarioPlanner(planningService, objectMapper),
                localMutationCoordinator,
                runner,
                new NoOpDemoJobCleanupPort(),
                modelDetailQueryPort,
                objectMapper
        );
    }

    private EqpQueryPort eqpQueryPort() {
        return new EqpQueryPort() {
            @Override
            public com.nori.tc.ui.core.model.PagedResponse<UiEqp> findAll(final com.nori.tc.ui.core.model.UiPageRequest pageRequest) {
                return com.nori.tc.ui.core.model.PagedResponse.empty(0, 1);
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
                        .filter(param -> java.util.Objects.equals(param.paramVersion(), version))
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

    private DeployCurrentState toCurrentState(final EqpManagementSnapshot snapshot) {
        return new DeployCurrentState(
                snapshot.eqp().eqpKey(),
                snapshot.eqp().eqpId(),
                snapshot.eqp().commInterface().name(),
                snapshot.eqp().commMode(),
                snapshot.eqp().eqpIp(),
                snapshot.eqp().eqpPort(),
                snapshot.eqp().modelVersionKey(),
                snapshot.eqp().appliedParamVersion(),
                snapshot.eqp().gatewayJarKey(),
                snapshot.eqp().businessJarKey(),
                snapshot.socket() == null ? null : snapshot.socket().socketProtocolType(),
                null,
                snapshot.eqp().routePartition(),
                // RECONNECT_GATEWAY step 은 controlState ∈ {REMOTE, LOCAL} 일 때만 통과한다
                // (DeployPlanningService#isReconnectStepAllowed). reconnect 통과 케이스를 위해 REMOTE 부여.
                remoteEquipmentProfile(snapshot)
        );
    }

    private UiEquipmentProfileSnapshot remoteEquipmentProfile(final EqpManagementSnapshot snapshot) {
        return new UiEquipmentProfileSnapshot(
                snapshot.eqp().eqpKey(),
                snapshot.eqp().eqpId(),
                snapshot.eqp().commInterface().name(),
                snapshot.socket() == null ? null : snapshot.socket().socketProtocolType(),
                null,
                snapshot.eqp().eqpIp(),
                snapshot.eqp().eqpPort(),
                snapshot.eqp().modelVersionKey(),
                snapshot.eqp().gatewayJarKey(),
                snapshot.eqp().businessJarKey(),
                snapshot.eqp().commMode(),
                snapshot.eqp().routePartition(),
                snapshot.eqp().enabled(),
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
                null
        );
    }

    private EqpManagementCommand.Create createCommand(final String eqpId) {
        return new EqpManagementCommand.Create(
                "tester",
                eqpId,
                UiProtocolType.SOCKET,
                "ACTIVE",
                false,
                1,
                "10.10.10.21",
                6021,
                1001L,
                null,
                null,
                null,
                new EqpManagementCommand.LogSettings(UiLogLevel.INFO, 7, "/tmp"),
                null,
                new EqpManagementCommand.SocketSettings("TEST01", "UTF-8", true, 30, 10, 10, 1024, true)
        );
    }

    private EqpManagementCommand.Update updateCommand(
            final String eqpIp,
            final int eqpPort,
            final String appliedParamVersion,
            final Long gatewayJarRef,
            final Long businessJarRef
    ) {
        return new EqpManagementCommand.Update(
                "tester",
                "ACTIVE",
                false,
                1,
                eqpIp,
                eqpPort,
                1002L,
                appliedParamVersion,
                gatewayJarRef,
                businessJarRef,
                new EqpManagementCommand.LogSettings(UiLogLevel.INFO, 7, "/tmp"),
                null,
                new EqpManagementCommand.SocketSettings("TEST01", "UTF-8", true, 30, 10, 10, 1024, true)
        );
    }

    private ObjectNode reasonUiMessage(final String reason) {
        final ObjectNode root = objectMapper.createObjectNode();
        root.put("reason", reason);
        return root;
    }

    private ObjectNode sendMessageUiMessage() {
        final ObjectNode root = objectMapper.createObjectNode();
        root.put("messageName", "EQP_CONDITION_CHECK");
        root.put("rawMessage", "PING");
        return root;
    }

    private UiModel model(final long versionKey, final String version) {
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new UiModel(
                versionKey,
                500L,
                "PHOTO_MODEL",
                null,
                version,
                UiProtocolType.SOCKET,
                UiModelStatus.OPERATE,
                null,
                null,
                now,
                now,
                "SYSTEM",
                "SYSTEM"
        );
    }

    private EqpManagementSnapshot snapshot(
            final String eqpId,
            final long eqpKey,
            final String eqpIp,
            final int eqpPort,
            final String appliedParamVersion,
            final Long gatewayJarRef,
            final Long businessJarRef
    ) {
        return snapshot(eqpId, eqpKey, eqpIp, eqpPort, appliedParamVersion, gatewayJarRef, businessJarRef, "ACTIVE");
    }

    private EqpManagementSnapshot snapshot(
            final String eqpId,
            final long eqpKey,
            final String eqpIp,
            final int eqpPort,
            final String appliedParamVersion,
            final Long gatewayJarRef,
            final Long businessJarRef,
            final String commMode
    ) {
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new EqpManagementSnapshot(
                new UiEqp(
                        eqpKey,
                        eqpId,
                        UiProtocolType.SOCKET,
                        commMode,
                        false,
                        1,
                        eqpIp,
                        eqpPort,
                        1001L,
                        appliedParamVersion,
                        gatewayJarRef,
                        businessJarRef,
                        true,
                        now,
                        now,
                        "SYSTEM",
                        "SYSTEM"
                ),
                model(1001L, "v1"),
                null,
                new UiEqpSocket(
                        eqpKey,
                        "TEST01",
                        "UTF-8",
                        true,
                        30,
                        10,
                        10,
                        1024,
                        true,
                        now,
                        now
                ),
                null,
                null,
                null,
                List.of(),
                List.of(new UiEqpParam(
                        1L,
                        eqpKey,
                        "PPID",
                        appliedParamVersion,
                        "P1001",
                        null,
                        "SYSTEM",
                        now
                )),
                List.of(),
                gatewayJarRef == null ? null : new UiGatewayJar(
                        gatewayJarRef,
                        "gateway-" + gatewayJarRef + ".jar",
                        ("gateway-" + gatewayJarRef).getBytes(),
                        now,
                        now,
                        "SYSTEM",
                        "SYSTEM"
                ),
                businessJarRef == null ? null : new UiBusinessJar(
                        businessJarRef,
                        "business-" + businessJarRef + ".jar",
                        ("business-" + businessJarRef).getBytes(),
                        now,
                        now,
                        "SYSTEM",
                        "SYSTEM"
                )
        );
    }

    private void await(final CountDownLatch latch) {
        try {
            assertTrue(latch.await(1, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
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
                final String loadedBy,
                final String reloadReason
        ) {
            latest = new DeployScenarioCatalogMeta(
                    ++sequence,
                    checksum,
                    scenarioCount,
                    OffsetDateTime.now(ZoneOffset.UTC),
                    loadedBy,
                    reloadReason
            );
            return latest;
        }
    }

    private static final class RecordingPublisher implements com.nori.tc.ui.core.port.messaging.UiGatewayEventPublishPort,
            com.nori.tc.ui.core.port.messaging.UiBusinessEventPublishPort {

        private final String source;
        private final DeployTraceRegistry traceRegistry;
        private final List<String> operationLog;
        private final Map<String, Integer> eventCounts = new LinkedHashMap<>();
        private final List<UiCommandMessage> messages = new ArrayList<>();
        private BiConsumer<UiCommandMessage, String> behavior;

        private RecordingPublisher(
                final String source,
                final DeployTraceRegistry traceRegistry,
                final List<String> operationLog
        ) {
            this.source = source;
            this.traceRegistry = traceRegistry;
            this.operationLog = operationLog;
            this.behavior = (message, ignored) -> replyPass(message);
        }

        @Override
        public void publish(final UiCommandMessage message) {
            messages.add(message);
            operationLog.add("publish:" + source + ":" + message.eventType().name());
            eventCounts.compute(message.eventType().name(), (eventType, count) -> count == null ? 1 : count + 1);
            behavior.accept(message, source);
        }

        private void replyPass(final UiCommandMessage message) {
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

        private void replyFail(
                final UiCommandMessage message,
                final String errorCode,
                final String errorMessage
        ) {
            traceRegistry.handle(new UiCommandReply(
                    message.traceId(),
                    source,
                    message.eventType().name() + "_REP",
                    message.eqpId(),
                    message.interfaceType(),
                    UiTaskStatus.FAIL,
                    errorCode,
                    errorMessage
            ));
        }

        private int countByEvent(final String eventType) {
            return eventCounts.getOrDefault(eventType, 0);
        }
    }

    private static final class InMemoryRepairTaskPort implements EqpRepairTaskPort {

        private final List<EqpRepairTask> tasks = new ArrayList<>();

        @Override
        public void save(final EqpRepairTask repairTask) {
            tasks.add(repairTask);
        }

        @Override
        public List<EqpRepairTask> findAll() {
            return List.copyOf(tasks);
        }
    }

    private static final class NoOpDemoJobCleanupPort implements DemoJobCleanupPort {

        @Override
        public List<String> findActiveDemoJobIds(final long eqpKey) {
            return List.of();
        }

        @Override
        public int deleteAllByEqpKey(final long eqpKey) {
            return 0;
        }
    }

    private static final class InMemoryDeployTraceRedisPort implements DeployTraceRedisPort {

        private final Map<String, TraceRecord> records = new LinkedHashMap<>();
        private java.util.function.Consumer<String> completionListener = traceId -> {
        };

        @Override
        public void register(final String traceId, final Set<String> expectedSources, final long timeoutMs) {
            records.put(traceId, new TraceRecord(expectedSources));
        }

        @Override
        public void record(final UiCommandReply reply) {
            final TraceRecord record = records.get(reply.traceId());
            if (record == null) {
                return;
            }
            record.replies.putIfAbsent(reply.source(), reply);
            if (record.replies.keySet().containsAll(record.expectedSources)) {
                completionListener.accept(reply.traceId());
            }
        }

        @Override
        public void cancel(final String traceId) {
            records.remove(traceId);
        }

        @Override
        public DeployTraceRedisLookupResult getCompletion(final String traceId) {
            final TraceRecord record = records.get(traceId);
            if (record == null) {
                return DeployTraceRedisLookupResult.pending();
            }
            if (!record.replies.keySet().containsAll(record.expectedSources)) {
                return DeployTraceRedisLookupResult.pending();
            }
            return DeployTraceRedisLookupResult.completed(
                    new com.nori.tc.ui.core.deploy.DeployTraceCompletion(
                            traceId,
                            record.expectedSources,
                            new ArrayList<>(record.replies.values())
                    )
            );
        }

        @Override
        public void subscribe(final java.util.function.Consumer<String> completionListener) {
            this.completionListener = completionListener;
        }

        private static final class TraceRecord {
            private final Set<String> expectedSources;
            private final Map<String, UiCommandReply> replies = new LinkedHashMap<>();

            private TraceRecord(final Set<String> expectedSources) {
                this.expectedSources = expectedSources;
            }
        }
    }

    private static final class InMemoryEqpCrudPort implements EqpCrudPort {

        private long eqpSequence = 100L;
        private final Map<String, EqpManagementSnapshot> snapshots = new LinkedHashMap<>();
        private List<String> operationLog = new ArrayList<>();

        @Override
        public EqpManagementSnapshot create(final EqpManagementCommand.Create command) {
            final long eqpKey = ++eqpSequence;
            final EqpManagementSnapshot created = snapshotFromCreate(command, eqpKey);
            snapshots.put(command.eqpId(), created);
            return created;
        }

        @Override
        public EqpManagementSnapshot update(
                final String eqpId,
                final EqpManagementCommand.Update command
        ) {
            final EqpManagementSnapshot current = snapshots.get(eqpId);
            final EqpManagementSnapshot updated = snapshotFromUpdate(current, command);
            snapshots.put(eqpId, updated);
            return updated;
        }

        @Override
        public void restore(final EqpManagementSnapshot snapshot) {
            snapshots.put(snapshot.eqp().eqpId(), snapshot);
        }

        @Override
        public void delete(final String eqpId) {
            operationLog.add("db-delete:" + eqpId);
            snapshots.remove(eqpId);
        }

        @Override
        public Optional<EqpManagementSnapshot> findSnapshotByEqpId(final String eqpId) {
            return Optional.ofNullable(snapshots.get(eqpId));
        }

        private void put(final EqpManagementSnapshot snapshot) {
            snapshots.put(snapshot.eqp().eqpId(), snapshot);
        }

        private EqpManagementSnapshot snapshotFromCreate(
                final EqpManagementCommand.Create command,
                final long eqpKey
        ) {
            final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            return new EqpManagementSnapshot(
                    new UiEqp(
                            eqpKey,
                            command.eqpId(),
                            command.interfaceType(),
                            command.commMode(),
                            command.isDev(),
                            command.routePartition(),
                            command.eqpIp(),
                            command.eqpPort(),
                            command.modelVersionKey(),
                            command.appliedParamVersion(),
                            command.gatewayJarRef(),
                            command.businessJarRef(),
                            true,
                            now,
                            now,
                            command.actor(),
                            command.actor()
                    ),
                    new UiModel(
                            command.modelVersionKey(),
                            500L,
                            "PHOTO_MODEL",
                            null,
                            command.modelVersionKey() == 1002L ? "v2" : "v1",
                            command.interfaceType(),
                            UiModelStatus.OPERATE,
                            null,
                            null,
                            now,
                            now,
                            "SYSTEM",
                            "SYSTEM"
                    ),
                    null,
                    command.socketSettings() == null ? null : new UiEqpSocket(
                            eqpKey,
                            command.socketSettings().socketProtocolType(),
                            command.socketSettings().charset(),
                            command.socketSettings().heartbeatEnabled(),
                            command.socketSettings().heartbeatInterval(),
                            command.socketSettings().readTimeout(),
                            command.socketSettings().writeTimeout(),
                            command.socketSettings().maxFrameSizeBytes(),
                            command.socketSettings().keepAliveEnabled(),
                            now,
                            now
                    ),
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(new UiEqpParam(
                            1L,
                            eqpKey,
                            "PPID",
                            command.appliedParamVersion(),
                            "P1001",
                            null,
                            command.actor(),
                            now
                    )),
                    List.of(),
                    command.gatewayJarRef() == null ? null : new UiGatewayJar(
                            command.gatewayJarRef(),
                            "gateway-" + command.gatewayJarRef() + ".jar",
                            ("gateway-" + command.gatewayJarRef()).getBytes(),
                            now,
                            now,
                            "SYSTEM",
                            "SYSTEM"
                    ),
                    command.businessJarRef() == null ? null : new UiBusinessJar(
                            command.businessJarRef(),
                            "business-" + command.businessJarRef() + ".jar",
                            ("business-" + command.businessJarRef()).getBytes(),
                            now,
                            now,
                            "SYSTEM",
                            "SYSTEM"
                    )
            );
        }

        private EqpManagementSnapshot snapshotFromUpdate(
                final EqpManagementSnapshot current,
                final EqpManagementCommand.Update command
        ) {
            final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            final long eqpKey = current.eqp().eqpKey();
            return new EqpManagementSnapshot(
                    new UiEqp(
                            eqpKey,
                            current.eqp().eqpId(),
                            current.eqp().commInterface(),
                            command.commMode(),
                            command.isDev(),
                            command.routePartition(),
                            command.eqpIp(),
                            command.eqpPort(),
                            command.modelVersionKey(),
                            command.appliedParamVersion(),
                            command.gatewayJarRef(),
                            command.businessJarRef(),
                            current.eqp().enabled(),
                            current.eqp().createdAt(),
                            now,
                            current.eqp().createdBy(),
                            command.actor()
                    ),
                    new UiModel(
                            command.modelVersionKey(),
                            500L,
                            "PHOTO_MODEL",
                            null,
                            command.modelVersionKey() == 1002L ? "v2" : "v1",
                            current.eqp().commInterface(),
                            UiModelStatus.OPERATE,
                            null,
                            null,
                            now,
                            now,
                            "SYSTEM",
                            "SYSTEM"
                    ),
                    null,
                    command.socketSettings() == null ? current.socket() : new UiEqpSocket(
                            eqpKey,
                            command.socketSettings().socketProtocolType(),
                            command.socketSettings().charset(),
                            command.socketSettings().heartbeatEnabled(),
                            command.socketSettings().heartbeatInterval(),
                            command.socketSettings().readTimeout(),
                            command.socketSettings().writeTimeout(),
                            command.socketSettings().maxFrameSizeBytes(),
                            command.socketSettings().keepAliveEnabled(),
                            now,
                            now
                    ),
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(new UiEqpParam(
                            1L,
                            eqpKey,
                            "PPID",
                            command.appliedParamVersion(),
                            "P1001",
                            null,
                            command.actor(),
                            now
                    )),
                    List.of(),
                    command.gatewayJarRef() == null ? null : new UiGatewayJar(
                            command.gatewayJarRef(),
                            "gateway-" + command.gatewayJarRef() + ".jar",
                            ("gateway-" + command.gatewayJarRef()).getBytes(),
                            now,
                            now,
                            "SYSTEM",
                            "SYSTEM"
                    ),
                    command.businessJarRef() == null ? null : new UiBusinessJar(
                            command.businessJarRef(),
                            "business-" + command.businessJarRef() + ".jar",
                            ("business-" + command.businessJarRef()).getBytes(),
                            now,
                            now,
                            "SYSTEM",
                            "SYSTEM"
                    )
            );
        }
    }
}
