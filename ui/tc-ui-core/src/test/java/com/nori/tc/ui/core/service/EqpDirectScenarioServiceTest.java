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
import com.nori.tc.ui.core.port.db.DeployScenarioCatalogMetaPort;
import com.nori.tc.ui.core.port.db.DeployStorePort;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import com.nori.tc.ui.core.port.db.EqpRepairTaskPort;
import com.nori.tc.ui.core.port.db.ModelCrudPort;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertEquals("MODEL_RUNTIME_FAILED", result.errorCode());
        assertTrue(eqpCrudPort.findSnapshotByEqpId("PHOTO_NEW").isEmpty());
        assertTrue(gatewayPublisher.messages.stream().anyMatch(message -> message.eventType().name().equals("EQP_DELETE")));
    }

    @Test
    void updateMidStepFailureRestoresPreviousSnapshot() {
        eqpCrudPort.put(snapshot("PHOTO_001", 1L, "10.10.10.1", 6000, "EQP_PARAM_V1", 3001L, 4001L));
        businessPublisher.behavior = (message, source) -> {
            if (message.eventType().name().equals("EQP_UPDATE") && businessPublisher.countByEvent("EQP_UPDATE") == 1) {
                businessPublisher.replyFail(message, "MODEL_RUNTIME_FAILED", "business runtime 적재 실패");
                return;
            }
            businessPublisher.replyPass(message);
        };

        final EqpCommandResult result = service.executeUpdate("PHOTO_001", updateCommand("10.10.10.9", 6100, "EQP_PARAM_V2", 3002L, 4002L));

        assertFalse(result.success());
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
    void parameterUpdateChangesLocalStateOnlyAfterReplySuccess() throws Exception {
        eqpCrudPort.put(snapshot("PHOTO_001", 1L, "10.10.10.1", 6000, "EQP_PARAM_V1", 3001L, 4001L));
        final CountDownLatch publishStarted = new CountDownLatch(1);
        final CountDownLatch releaseReply = new CountDownLatch(1);
        businessPublisher.behavior = (message, source) -> {
            publishStarted.countDown();
            await(releaseReply);
            businessPublisher.replyPass(message);
        };

        final CompletableFuture<EqpCommandResult> future = CompletableFuture.supplyAsync(
                () -> service.executeParameterUpdate("PHOTO_001", "EQP_PARAM_V2", "tester")
        );

        assertTrue(publishStarted.await(1, TimeUnit.SECONDS));
        assertEquals("EQP_PARAM_V1", eqpCrudPort.findSnapshotByEqpId("PHOTO_001").orElseThrow().eqp().appliedParamVersion());
        releaseReply.countDown();

        assertTrue(future.get(1, TimeUnit.SECONDS).success());
        assertEquals("EQP_PARAM_V2", eqpCrudPort.findSnapshotByEqpId("PHOTO_001").orElseThrow().eqp().appliedParamVersion());
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
        final CountDownLatch bindingPublishStarted = new CountDownLatch(1);
        final CountDownLatch releaseBindingReply = new CountDownLatch(1);
        businessPublisher.behavior = (message, source) -> {
            if (message.eventType().name().equals("EQP_BUSINESS_JARFILE_UPDATE")) {
                businessPublisher.replyPass(message);
                return;
            }
            bindingPublishStarted.countDown();
            await(releaseBindingReply);
            businessPublisher.replyPass(message);
        };

        final CompletableFuture<EqpCommandResult> future = CompletableFuture.supplyAsync(
                () -> service.executeBusinessJarUpdate("PHOTO_001", 4002L, "tester")
        );

        assertTrue(bindingPublishStarted.await(1, TimeUnit.SECONDS));
        assertEquals(4001L, eqpCrudPort.findSnapshotByEqpId("PHOTO_001").orElseThrow().eqp().businessJarKey());
        releaseBindingReply.countDown();

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

        final DeployServiceCommandSupport commandSupport = new DeployServiceCommandSupport(
                mock(DeployStorePort.class),
                eqpCrudPort,
                gatewayPublisher,
                businessPublisher,
                objectMapper
        );
        final DeployCommandAssembler commandAssembler = new DeployCommandAssembler(commandSupport);
        final EqpRepairTaskService repairTaskService = new EqpRepairTaskService(repairTaskPort);
        final EqpPersistenceService persistenceService = new EqpPersistenceService(eqpCrudPort);
        final EqpManagementCommandValidator validator = new EqpManagementCommandValidator(modelCrudPort);
        final EqpDirectScenarioLocalMutationCoordinator localMutationCoordinator =
                new EqpDirectScenarioLocalMutationCoordinator(persistenceService, eqpCrudPort, objectMapper);
        final DeployTraceRecorder traceRecorder = new DeployTraceRecorder(traceRegistry);
        final EqpDirectScenarioRollbackService rollbackService = new EqpDirectScenarioRollbackService(
                commandAssembler,
                traceRecorder,
                repairTaskService,
                localMutationCoordinator,
                eqpCrudPort,
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
                objectMapper
        );
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
                "EQP_PARAM_V1",
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
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new EqpManagementSnapshot(
                new UiEqp(
                        eqpKey,
                        eqpId,
                        UiProtocolType.SOCKET,
                        "ACTIVE",
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
            eventCounts.merge(message.eventType().name(), 1, Integer::sum);
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
