package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import com.nori.tc.messaging.kafka.contract.GatewayEquipmentProfileSnapshot;
import com.nori.tc.db.domain.common.eqp.EqpState;
import com.nori.tc.db.domain.common.model.ModelStatus;
import com.nori.tc.db.domain.common.model.ProtocolType;
import com.nori.tc.db.domain.eqp.TcEqpParam;
import com.nori.tc.db.domain.eqp.TcEqpPortStatus;
import com.nori.tc.db.domain.model.TcModel;
import com.nori.tc.ui.core.eqp.EqpCommandResult;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementOptions;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.model.AsyncResultEntry;
import com.nori.tc.ui.core.model.AsyncStatus;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.model.UiCommandMessage;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import com.nori.tc.ui.core.port.db.EqpManageQueryPort;
import com.nori.tc.ui.core.port.db.EqpOptionsQueryPort;
import com.nori.tc.ui.core.port.db.ModelCrudPort;
import com.nori.tc.ui.core.port.messaging.UiBusinessEventPublishPort;
import com.nori.tc.ui.core.port.messaging.UiGatewayEventPublishPort;
import com.nori.tc.ui.core.port.redis.AsyncResultStorePort;
import com.nori.tc.ui.core.registry.DualResponseRegistry;
import com.nori.tc.ui.core.registry.UiDualTaskFinalResult;
import com.nori.tc.ui.domain.task.UiTaskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * EQP 관리 생성/수정/삭제와 옵션/상세 조회를 총괄하는 서비스입니다.
 *
 * <p>웹 계층은 본 서비스를 호출한 뒤 결과를 HTTP 응답으로만 변환합니다.</p>
 */
@Service
public class EqpManagementService {

    public static final String EQP_MANAGEMENT_EXECUTOR_BEAN_NAME = "uiEqpManagementExecutor";

    private static final Logger log = LoggerFactory.getLogger(EqpManagementService.class);

    private static final int BAD_REQUEST_STATUS = 400;
    private static final int NOT_FOUND_STATUS = 404;
    private static final int CONFLICT_STATUS = 409;
    private static final int INTERNAL_ERROR_STATUS = 500;
    private static final int TIMEOUT_STATUS = 504;
    private static final long ASYNC_POLL_INTERVAL_MS = 100L;
    private static final String UI_SOURCE = "TC-UI-APP";

    private final EqpCrudPort eqpCrudPort;
    private final EqpManageQueryPort eqpManageQueryPort;
    private final EqpOptionsQueryPort eqpOptionsQueryPort;
    private final ModelCrudPort modelCrudPort;
    private final DualResponseRegistry dualResponseRegistry;
    private final UiGatewayEventPublishPort gatewayEventPublishPort;
    private final UiBusinessEventPublishPort businessEventPublishPort;
    private final AsyncResultStorePort asyncResultStorePort;
    private final Executor eqpManagementExecutor;

    /**
     * 필수 의존성을 초기화합니다.
     */
    public EqpManagementService(
            final EqpCrudPort eqpCrudPort,
            final EqpManageQueryPort eqpManageQueryPort,
            final EqpOptionsQueryPort eqpOptionsQueryPort,
            final ModelCrudPort modelCrudPort,
            final DualResponseRegistry dualResponseRegistry,
            final UiGatewayEventPublishPort gatewayEventPublishPort,
            final UiBusinessEventPublishPort businessEventPublishPort,
            final AsyncResultStorePort asyncResultStorePort,
            @Qualifier(EQP_MANAGEMENT_EXECUTOR_BEAN_NAME) final Executor eqpManagementExecutor
    ) {
        this.eqpCrudPort = Objects.requireNonNull(eqpCrudPort, "eqpCrudPort is null");
        this.eqpManageQueryPort = Objects.requireNonNull(eqpManageQueryPort, "eqpManageQueryPort is null");
        this.eqpOptionsQueryPort = Objects.requireNonNull(eqpOptionsQueryPort, "eqpOptionsQueryPort is null");
        this.modelCrudPort = Objects.requireNonNull(modelCrudPort, "modelCrudPort is null");
        this.dualResponseRegistry = Objects.requireNonNull(dualResponseRegistry, "dualResponseRegistry is null");
        this.gatewayEventPublishPort = Objects.requireNonNull(gatewayEventPublishPort, "gatewayEventPublishPort is null");
        this.businessEventPublishPort = Objects.requireNonNull(businessEventPublishPort, "businessEventPublishPort is null");
        this.asyncResultStorePort = Objects.requireNonNull(asyncResultStorePort, "asyncResultStorePort is null");
        this.eqpManagementExecutor = Objects.requireNonNull(eqpManagementExecutor, "eqpManagementExecutor is null");
    }

    /**
     * EQP 관리 상세를 조회합니다.
     *
     * @param eqpId 조회 대상 eqp id
     * @return 관리 스냅샷
     */
    public Optional<EqpManagementSnapshot> getManageDetail(final String eqpId) {
        validateEqpId(eqpId);
        return eqpManageQueryPort.findManageSnapshotByEqpId(eqpId);
    }

    /**
     * EQP 관리 옵션을 조회합니다.
     *
     * @return 관리 옵션
     */
    public EqpManagementOptions getOptions() {
        return eqpOptionsQueryPort.loadOptions();
    }

    /**
     * EQP를 생성하고 runtime sync까지 완료합니다.
     *
     * @param command 생성 명령
     * @param timeoutMs dual/lifecycle timeout
     * @return 최종 처리 결과
     */
    public CompletableFuture<EqpCommandResult> create(
            final EqpManagementCommand.Create command,
            final long timeoutMs
    ) {
        return CompletableFuture.supplyAsync(() -> doCreate(command, timeoutMs), eqpManagementExecutor);
    }

    /**
     * EQP를 수정하고 runtime sync까지 완료합니다.
     *
     * @param eqpId 수정 대상 eqp id
     * @param command 수정 명령
     * @param timeoutMs dual/lifecycle timeout
     * @return 최종 처리 결과
     */
    public CompletableFuture<EqpCommandResult> update(
            final String eqpId,
            final EqpManagementCommand.Update command,
            final long timeoutMs
    ) {
        return CompletableFuture.supplyAsync(() -> doUpdate(eqpId, command, timeoutMs), eqpManagementExecutor);
    }

    /**
     * EQP를 종료 후 삭제하고 runtime sync까지 완료합니다.
     *
     * @param eqpId 삭제 대상 eqp id
     * @param timeoutMs dual/lifecycle timeout
     * @return 최종 처리 결과
     */
    public CompletableFuture<EqpCommandResult> delete(
            final String eqpId,
            final long timeoutMs
    ) {
        return CompletableFuture.supplyAsync(() -> doDelete(eqpId, timeoutMs), eqpManagementExecutor);
    }

    private EqpCommandResult doCreate(
            final EqpManagementCommand.Create command,
            final long timeoutMs
    ) {
        EqpManagementSnapshot createdSnapshot = null;

        try {
            validateCreateCommand(command);
            validateModelBinding(command.interfaceType(), command.isDev(), command.modelVersionKey());

            createdSnapshot = eqpCrudPort.create(command);
            awaitDualSuccess(UiCommandEventType.EQP_CREATE, createdSnapshot, timeoutMs);

            if (requiresJarReload(null, createdSnapshot)) {
                awaitJarReloadSuccess(createdSnapshot, timeoutMs);
            }

            return EqpCommandResult.ok();
        } catch (Exception exception) {
            if (createdSnapshot != null) {
                safeRuntimeDelete(createdSnapshot, timeoutMs);
                safeDelete(createdSnapshot.eqp().eqpId());
            }
            return toCommandResult(exception);
        }
    }

    private EqpCommandResult doUpdate(
            final String eqpId,
            final EqpManagementCommand.Update command,
            final long timeoutMs
    ) {
        validateEqpId(eqpId);

        final Optional<EqpManagementSnapshot> existingOptional = eqpCrudPort.findSnapshotByEqpId(eqpId);
        if (existingOptional.isEmpty()) {
            return EqpCommandResult.error(NOT_FOUND_STATUS, "NOT_FOUND", "설비를 찾을 수 없습니다.");
        }

        final EqpManagementSnapshot existingSnapshot = existingOptional.get();
        boolean persisted = false;

        try {
            validateUpdateCommand(command, existingSnapshot);
            validateModelBinding(existingSnapshot.eqp().commInterface(), command.isDev(), command.modelVersionKey());

            final EqpManagementSnapshot updatedSnapshot = eqpCrudPort.update(eqpId, command);
            persisted = true;
            awaitDualSuccess(UiCommandEventType.EQP_UPDATE, updatedSnapshot, timeoutMs);

            if (requiresJarReload(existingSnapshot, updatedSnapshot)) {
                awaitJarReloadSuccess(updatedSnapshot, timeoutMs);
            }

            return EqpCommandResult.ok();
        } catch (Exception exception) {
            if (persisted) {
                safeRestore(existingSnapshot);
                safeRuntimeUpdate(existingSnapshot, timeoutMs);
                if (hasAnyJar(existingSnapshot)) {
                    safeJarReload(existingSnapshot, timeoutMs);
                }
            }
            return toCommandResult(exception);
        }
    }

    private EqpCommandResult doDelete(
            final String eqpId,
            final long timeoutMs
    ) {
        validateEqpId(eqpId);

        final Optional<EqpManagementSnapshot> existingOptional = eqpCrudPort.findSnapshotByEqpId(eqpId);
        if (existingOptional.isEmpty()) {
            return EqpCommandResult.error(NOT_FOUND_STATUS, "NOT_FOUND", "설비를 찾을 수 없습니다.");
        }

        final EqpManagementSnapshot snapshot = existingOptional.get();
        boolean deletedFromDb = false;

        try {
            if (!isAlreadyStopped(snapshot)) {
                awaitLifecycleSuccess(UiCommandEventType.EQP_END, snapshot, timeoutMs);
            }

            eqpCrudPort.delete(eqpId);
            deletedFromDb = true;
            awaitDualSuccess(UiCommandEventType.EQP_DELETE, snapshot, timeoutMs);

            return EqpCommandResult.ok();
        } catch (Exception exception) {
            if (deletedFromDb) {
                safeRestore(snapshot);
                safeRuntimeCreate(snapshot, timeoutMs);
                if (hasAnyJar(snapshot)) {
                    safeJarReload(snapshot, timeoutMs);
                }
            }
            return toCommandResult(exception);
        }
    }

    private void validateCreateCommand(final EqpManagementCommand.Create command) {
        if (command == null) {
            throw new UiBadRequestException("요청 본문이 비어 있습니다.");
        }
        if (command.eqpId() == null || command.eqpId().isBlank()) {
            throw new UiBadRequestException("eqpId는 필수입니다.");
        }
        if (command.interfaceType() == null) {
            throw new UiBadRequestException("interfaceType은 필수입니다.");
        }
        if (command.commMode() == null || command.commMode().isBlank()) {
            throw new UiBadRequestException("commMode는 필수입니다.");
        }

        validateEditableFields(
                command.routePartition(),
                command.eqpIp(),
                command.eqpPort(),
                command.modelVersionKey(),
                command.interfaceType(),
                command.hsmsSettings(),
                command.socketSettings()
        );
    }

    private void validateUpdateCommand(
            final EqpManagementCommand.Update command,
            final EqpManagementSnapshot existingSnapshot
    ) {
        if (command == null) {
            throw new UiBadRequestException("요청 본문이 비어 있습니다.");
        }
        if (command.commMode() == null || command.commMode().isBlank()) {
            throw new UiBadRequestException("commMode는 필수입니다.");
        }

        validateEditableFields(
                command.routePartition(),
                command.eqpIp(),
                command.eqpPort(),
                command.modelVersionKey(),
                existingSnapshot.eqp().commInterface(),
                command.hsmsSettings(),
                command.socketSettings()
        );
    }

    private void validateEditableFields(
            final Integer routePartition,
            final String eqpIp,
            final Integer eqpPort,
            final long modelVersionKey,
            final ProtocolType interfaceType,
            final EqpManagementCommand.HsmsSettings hsmsSettings,
            final EqpManagementCommand.SocketSettings socketSettings
    ) {
        if (routePartition == null || routePartition < 0) {
            throw new UiBadRequestException("routePartition은 0 이상이어야 합니다.");
        }
        if (eqpIp == null || eqpIp.isBlank()) {
            throw new UiBadRequestException("eqpIp는 필수입니다.");
        }
        if (eqpPort == null || eqpPort <= 0) {
            throw new UiBadRequestException("eqpPort는 1 이상이어야 합니다.");
        }
        if (modelVersionKey <= 0) {
            throw new UiBadRequestException("modelVersionKey는 1 이상이어야 합니다.");
        }

        if (interfaceType == ProtocolType.SECS && hsmsSettings == null) {
            throw new UiBadRequestException("SECS 설비는 hsmsSettings가 필요합니다.");
        }
        if (interfaceType == ProtocolType.SOCKET && socketSettings == null) {
            throw new UiBadRequestException("SOCKET 설비는 socketSettings가 필요합니다.");
        }
    }

    private void validateModelBinding(
            final ProtocolType eqpInterfaceType,
            final boolean isDev,
            final long modelVersionKey
    ) {
        final Optional<TcModel> modelOptional = modelCrudPort.findByModelVersionKey(modelVersionKey);
        if (modelOptional.isEmpty()) {
            throw new UiBadRequestException("연결할 모델을 찾을 수 없습니다.");
        }

        final TcModel model = modelOptional.get();
        if (model.commInterface() != eqpInterfaceType) {
            throw new UiBadRequestException("설비 통신 인터페이스와 모델 통신 인터페이스가 일치하지 않습니다.");
        }

        final ModelStatus expectedStatus = isDev ? ModelStatus.DEVELOP : ModelStatus.OPERATE;
        if (model.status() != expectedStatus) {
            throw new UiBadRequestException(
                    "개발/운영 장비 정책과 맞지 않는 모델 상태입니다. requiredStatus=" + expectedStatus.name()
            );
        }
    }

    private void validateEqpId(final String eqpId) {
        if (eqpId == null || eqpId.isBlank()) {
            throw new UiBadRequestException("eqpId는 비어 있을 수 없습니다.");
        }
    }

    private boolean requiresJarReload(
            final EqpManagementSnapshot before,
            final EqpManagementSnapshot after
    ) {
        final String beforeGateway = before == null || before.gatewayJar() == null
                ? null
                : normalizeText(before.gatewayJar().jarFileName());
        final String afterGateway = after == null || after.gatewayJar() == null
                ? null
                : normalizeText(after.gatewayJar().jarFileName());
        final String beforeBusiness = before == null || before.businessJar() == null
                ? null
                : normalizeText(before.businessJar().jarFileName());
        final String afterBusiness = after == null || after.businessJar() == null
                ? null
                : normalizeText(after.businessJar().jarFileName());

        return !Objects.equals(beforeGateway, afterGateway) || !Objects.equals(beforeBusiness, afterBusiness);
    }

    private boolean hasAnyJar(final EqpManagementSnapshot snapshot) {
        return snapshot != null && (snapshot.gatewayJar() != null || snapshot.businessJar() != null);
    }

    private boolean isAlreadyStopped(final EqpManagementSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }

        final boolean eqpStateDown = snapshot.runtimeState() != null
                && snapshot.runtimeState().eqpState() == EqpState.DOWN;
        final boolean disconnected = "DISCONNECTED".equalsIgnoreCase(normalizeText(snapshot.connectionState()));

        return eqpStateDown || disconnected;
    }

    private void awaitDualSuccess(
            final UiCommandEventType eventType,
            final EqpManagementSnapshot snapshot,
            final long timeoutMs
    ) throws Exception {
        final String traceId = UUID.randomUUID().toString();
        final String eqpId = snapshot.eqp().eqpId();
        final String interfaceType = snapshot.eqp().commInterface().name();
        final JsonNode uiMessage = buildUiMessage(snapshot.eqp().modelVersionKey());
        final GatewayEquipmentProfileSnapshot equipmentProfile =
                (eventType == UiCommandEventType.EQP_CREATE || eventType == UiCommandEventType.EQP_UPDATE)
                        ? toEquipmentProfile(snapshot)
                        : null;

        final CompletableFuture<UiDualTaskFinalResult> future = dualResponseRegistry.register(traceId, timeoutMs);
        final UiCommandMessage message = new UiCommandMessage(
                eventType,
                traceId,
                UI_SOURCE,
                eqpId,
                interfaceType,
                uiMessage,
                equipmentProfile
        );

        boolean gatewayPublished = false;
        try {
            gatewayEventPublishPort.publish(message);
            gatewayPublished = true;
            businessEventPublishPort.publish(message);
        } catch (Exception publishException) {
            dualResponseRegistry.cancel(traceId);
            throw new CommandFailureException(
                    INTERNAL_ERROR_STATUS,
                    "PUBLISH_FAILED",
                    "Kafka 발행 중 오류가 발생했습니다.",
                    publishException
            );
        }

        try {
            final UiDualTaskFinalResult finalResult = future.join();
            if (!finalResult.success()) {
                final UiTaskResult failed = finalResult.firstFailedResult().orElse(null);
                final String errorCode = failed == null ? "PROCESSING_FAILED" : failed.errorCode();
                final String errorMessage = failed == null ? "설비 동기화에 실패했습니다." : failed.errorMsg();
                throw new CommandFailureException(
                        INTERNAL_ERROR_STATUS,
                        errorCode,
                        errorMessage,
                        null
                );
            }
        } catch (CompletionException completionException) {
            final Throwable cause = completionException.getCause();
            if (cause instanceof TimeoutException) {
                throw new CommandFailureException(
                        TIMEOUT_STATUS,
                        "TIMEOUT",
                        "Gateway/Business 응답 대기 시간이 초과되었습니다.",
                        cause
                );
            }
            if (cause instanceof CancellationException) {
                throw new CommandFailureException(
                        INTERNAL_ERROR_STATUS,
                        "PUBLISH_FAILED",
                        "Kafka 발행 중 오류가 발생했습니다.",
                        cause
                );
            }
            if (cause instanceof CommandFailureException commandFailureException) {
                throw commandFailureException;
            }

            throw new CommandFailureException(
                    INTERNAL_ERROR_STATUS,
                    "INTERNAL_ERROR",
                    "설비 동기화 처리 중 내부 오류가 발생했습니다.",
                    cause
            );
        } finally {
            if (!gatewayPublished) {
                dualResponseRegistry.cancel(traceId);
            }
        }
    }

    private void awaitLifecycleSuccess(
            final UiCommandEventType eventType,
            final EqpManagementSnapshot snapshot,
            final long timeoutMs
    ) throws Exception {
        final String traceId = UUID.randomUUID().toString();
        final UiCommandMessage message = new UiCommandMessage(
                eventType,
                traceId,
                UI_SOURCE,
                snapshot.eqp().eqpId(),
                snapshot.eqp().commInterface().name(),
                buildUiMessage(snapshot.eqp().modelVersionKey()),
                null
        );

        asyncResultStorePort.registerPending(traceId, timeoutMs);

        try {
            gatewayEventPublishPort.publish(message);
        } catch (Exception publishException) {
            asyncResultStorePort.markTimeout(traceId);
            throw new CommandFailureException(
                    INTERNAL_ERROR_STATUS,
                    "PUBLISH_FAILED",
                    "Kafka 발행 중 오류가 발생했습니다.",
                    publishException
            );
        }

        final long deadlineEpochMs = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() <= deadlineEpochMs) {
            final Optional<AsyncResultEntry> entryOptional = asyncResultStorePort.getWithStatus(traceId);
            if (entryOptional.isPresent()) {
                final AsyncResultEntry entry = entryOptional.get();
                if (entry.status() == AsyncStatus.COMPLETED) {
                    final UiCommandReply reply = entry.reply();
                    if (reply != null && reply.isSuccess()) {
                        return;
                    }

                    throw new CommandFailureException(
                            CONFLICT_STATUS,
                            reply == null ? "EQP_END_FAILED" : reply.errorCode(),
                            reply == null ? "설비 종료에 실패했습니다." : reply.errorMsg(),
                            null
                    );
                }

                if (entry.status() == AsyncStatus.TIMEOUT) {
                    throw new CommandFailureException(
                            TIMEOUT_STATUS,
                            "TIMEOUT",
                            "설비 종료 응답 대기 시간이 초과되었습니다.",
                            null
                    );
                }
            }

            TimeUnit.MILLISECONDS.sleep(ASYNC_POLL_INTERVAL_MS);
        }

        asyncResultStorePort.markTimeout(traceId);
        throw new CommandFailureException(
                TIMEOUT_STATUS,
                "TIMEOUT",
                "설비 종료 응답 대기 시간이 초과되었습니다.",
                null
        );
    }

    private GatewayEquipmentProfileSnapshot toEquipmentProfile(final EqpManagementSnapshot snapshot) {
        final OffsetDateTime updatedAt = snapshot.eqp().updatedAt() == null
                ? OffsetDateTime.now()
                : snapshot.eqp().updatedAt();

        final GatewayEquipmentProfileSnapshot.HsmsSettingsSnapshot hsmsSettings =
                snapshot.hsms() == null ? null : new GatewayEquipmentProfileSnapshot.HsmsSettingsSnapshot(
                        snapshot.hsms().deviceId(),
                        snapshot.eqp().commMode(),
                        snapshot.hsms().t3Timeout(),
                        snapshot.hsms().t5Timeout(),
                        snapshot.hsms().t6Timeout(),
                        snapshot.hsms().t7Timeout(),
                        snapshot.hsms().t8Timeout(),
                        snapshot.hsms().linkTestEnabled(),
                        snapshot.hsms().linkTestInterval(),
                        snapshot.hsms().maxMsgBytes()
                );

        final GatewayEquipmentProfileSnapshot.SocketSettingsSnapshot socketSettings =
                snapshot.socket() == null ? null : new GatewayEquipmentProfileSnapshot.SocketSettingsSnapshot(
                        snapshot.socket().socketProtocolType(),
                        snapshot.eqp().commMode(),
                        snapshot.socket().charset(),
                        snapshot.socket().heartbeatEnabled(),
                        snapshot.socket().heartbeatInterval(),
                        snapshot.socket().readTimeout(),
                        snapshot.socket().writeTimeout(),
                        snapshot.socket().maxFrameSizeBytes(),
                        snapshot.socket().keepAliveEnabled()
                );

        final GatewayEquipmentProfileSnapshot.CurrentStateSnapshot currentStateSnapshot =
                snapshot.runtimeState() == null ? null : new GatewayEquipmentProfileSnapshot.CurrentStateSnapshot(
                        snapshot.runtimeState().controlState() == null ? null : snapshot.runtimeState().controlState().name(),
                        snapshot.runtimeState().eqpState() == null ? null : snapshot.runtimeState().eqpState().name(),
                        snapshot.runtimeState().sinceAt() == null ? null : snapshot.runtimeState().sinceAt().toString(),
                        snapshot.runtimeState().reasonCode(),
                        snapshot.runtimeState().reasonDetail(),
                        snapshot.runtimeState().updatedAt() == null ? null : snapshot.runtimeState().updatedAt().toString()
                );

        final List<GatewayEquipmentProfileSnapshot.PortStatusSnapshot> portStatuses = snapshot.portStatuses().stream()
                .map(this::toPortStatusSnapshot)
                .toList();
        final List<GatewayEquipmentProfileSnapshot.ParamSnapshot> params = snapshot.params().stream()
                .map(this::toParamSnapshot)
                .toList();

        final GatewayEquipmentProfileSnapshot.LogPolicySnapshot logPolicy =
                snapshot.logPolicy() == null ? null : new GatewayEquipmentProfileSnapshot.LogPolicySnapshot(
                        snapshot.logPolicy().logLevel() == null ? null : snapshot.logPolicy().logLevel().name(),
                        snapshot.logPolicy().logRetentionDays(),
                        snapshot.logPolicy().logPath(),
                        snapshot.logPolicy().updatedAt() == null ? null : snapshot.logPolicy().updatedAt().toString()
                );

        return new GatewayEquipmentProfileSnapshot(
                snapshot.eqp().eqpKey(),
                snapshot.eqp().eqpId(),
                snapshot.eqp().commInterface().name(),
                snapshot.socket() == null ? null : snapshot.socket().socketProtocolType(),
                snapshot.hsms() == null ? null : snapshot.hsms().deviceId(),
                snapshot.eqp().eqpIp(),
                snapshot.eqp().eqpPort(),
                snapshot.eqp().modelVersionKey(),
                snapshot.eqp().commMode(),
                snapshot.eqp().routePartition(),
                snapshot.eqp().enabled(),
                hsmsSettings,
                socketSettings,
                currentStateSnapshot,
                portStatuses,
                logPolicy,
                params,
                updatedAt.toString()
        );
    }

    private GatewayEquipmentProfileSnapshot.PortStatusSnapshot toPortStatusSnapshot(final TcEqpPortStatus status) {
        return new GatewayEquipmentProfileSnapshot.PortStatusSnapshot(
                status.portId(),
                status.portType() == null ? null : status.portType().name(),
                status.portState() == null ? null : status.portState().name(),
                status.carrierId(),
                status.carrierType() == null ? null : status.carrierType().name(),
                status.carrierState() == null ? null : status.carrierState().name(),
                status.updatedAt() == null ? null : status.updatedAt().toString()
        );
    }

    private GatewayEquipmentProfileSnapshot.ParamSnapshot toParamSnapshot(final TcEqpParam param) {
        return new GatewayEquipmentProfileSnapshot.ParamSnapshot(
                param.eqpParamKey(),
                param.paramName(),
                param.paramVersion(),
                param.paramValue(),
                param.updatedAt() == null ? null : param.updatedAt().toString()
        );
    }

    private void awaitJarReloadSuccess(
            final EqpManagementSnapshot snapshot,
            final long timeoutMs
    ) throws Exception {
        final String traceId = UUID.randomUUID().toString();
        final String eqpId = snapshot.eqp().eqpId();
        final String interfaceType = snapshot.eqp().commInterface().name();
        final JsonNode uiMessage = buildUiMessage(snapshot.eqp().modelVersionKey());

        final CompletableFuture<UiDualTaskFinalResult> future = dualResponseRegistry.register(traceId, timeoutMs);
        final UiCommandMessage gatewayMessage = new UiCommandMessage(
                UiCommandEventType.EQP_GATEWAY_JARFILE_UPDATE,
                traceId,
                UI_SOURCE,
                eqpId,
                interfaceType,
                uiMessage,
                null
        );
        final UiCommandMessage businessMessage = new UiCommandMessage(
                UiCommandEventType.EQP_BUSINESS_JARFILE_UPDATE,
                traceId,
                UI_SOURCE,
                eqpId,
                interfaceType,
                uiMessage,
                null
        );

        boolean gatewayPublished = false;
        try {
            gatewayEventPublishPort.publish(gatewayMessage);
            gatewayPublished = true;
            businessEventPublishPort.publish(businessMessage);
        } catch (Exception publishException) {
            dualResponseRegistry.cancel(traceId);
            throw new CommandFailureException(
                    INTERNAL_ERROR_STATUS,
                    "PUBLISH_FAILED",
                    "Kafka 발행 중 오류가 발생했습니다.",
                    publishException
            );
        }

        try {
            final UiDualTaskFinalResult finalResult = future.join();
            if (!finalResult.success()) {
                final UiTaskResult failed = finalResult.firstFailedResult().orElse(null);
                final String errorCode = failed == null ? "PROCESSING_FAILED" : failed.errorCode();
                final String errorMessage = failed == null ? "설비 JAR 동기화에 실패했습니다." : failed.errorMsg();
                throw new CommandFailureException(
                        INTERNAL_ERROR_STATUS,
                        errorCode,
                        errorMessage,
                        null
                );
            }
        } catch (CompletionException completionException) {
            final Throwable cause = completionException.getCause();
            if (cause instanceof TimeoutException) {
                throw new CommandFailureException(
                        TIMEOUT_STATUS,
                        "TIMEOUT",
                        "Gateway/Business 응답 대기 시간이 초과되었습니다.",
                        cause
                );
            }
            if (cause instanceof CancellationException) {
                throw new CommandFailureException(
                        INTERNAL_ERROR_STATUS,
                        "PUBLISH_FAILED",
                        "Kafka 발행 중 오류가 발생했습니다.",
                        cause
                );
            }
            if (cause instanceof CommandFailureException commandFailureException) {
                throw commandFailureException;
            }

            throw new CommandFailureException(
                    INTERNAL_ERROR_STATUS,
                    "INTERNAL_ERROR",
                    "설비 JAR 동기화 처리 중 내부 오류가 발생했습니다.",
                    cause
            );
        } finally {
            if (!gatewayPublished) {
                dualResponseRegistry.cancel(traceId);
            }
        }
    }

    private JsonNode buildUiMessage(final long modelVersionKey) {
        final ObjectNode modelInfo = JsonNodeFactory.instance.objectNode();
        modelInfo.put("modelVersionKey", modelVersionKey);

        final ObjectNode uiMessage = JsonNodeFactory.instance.objectNode();
        uiMessage.set("modelInfo", modelInfo);
        return uiMessage;
    }

    private void safeRuntimeDelete(final EqpManagementSnapshot snapshot, final long timeoutMs) {
        try {
            awaitDualSuccess(UiCommandEventType.EQP_DELETE, snapshot, timeoutMs);
        } catch (Exception exception) {
            log.error("EQP create 보상용 runtime delete 실패. eqpId={}", snapshot.eqp().eqpId(), exception);
        }
    }

    private void safeRuntimeUpdate(final EqpManagementSnapshot snapshot, final long timeoutMs) {
        try {
            awaitDualSuccess(UiCommandEventType.EQP_UPDATE, snapshot, timeoutMs);
        } catch (Exception exception) {
            log.error("EQP update 보상용 runtime sync 실패. eqpId={}", snapshot.eqp().eqpId(), exception);
        }
    }

    private void safeRuntimeCreate(final EqpManagementSnapshot snapshot, final long timeoutMs) {
        try {
            awaitDualSuccess(UiCommandEventType.EQP_CREATE, snapshot, timeoutMs);
        } catch (Exception exception) {
            log.error("EQP delete 보상용 runtime recreate 실패. eqpId={}", snapshot.eqp().eqpId(), exception);
        }
    }

    private void safeJarReload(final EqpManagementSnapshot snapshot, final long timeoutMs) {
        try {
            awaitJarReloadSuccess(snapshot, timeoutMs);
        } catch (Exception exception) {
            log.error("EQP jar reload 보상 실패. eqpId={}", snapshot.eqp().eqpId(), exception);
        }
    }

    private void safeDelete(final String eqpId) {
        try {
            eqpCrudPort.delete(eqpId);
        } catch (Exception exception) {
            log.error("EQP create 보상용 DB delete 실패. eqpId={}", eqpId, exception);
        }
    }

    private void safeRestore(final EqpManagementSnapshot snapshot) {
        try {
            eqpCrudPort.restore(snapshot);
        } catch (Exception exception) {
            log.error("EQP 스냅샷 복구 실패. eqpId={}", snapshot.eqp().eqpId(), exception);
        }
    }

    private EqpCommandResult toCommandResult(final Exception exception) {
        if (exception instanceof UiBadRequestException uiBadRequestException) {
            return EqpCommandResult.error(BAD_REQUEST_STATUS, "INVALID_REQUEST", uiBadRequestException.getMessage());
        }

        if (exception instanceof CommandFailureException commandFailureException) {
            return EqpCommandResult.error(
                    commandFailureException.statusCode(),
                    commandFailureException.errorCode(),
                    commandFailureException.getMessage()
            );
        }

        if (exception instanceof UiConflictException uiConflictException) {
            return EqpCommandResult.error(CONFLICT_STATUS, "CONFLICT", uiConflictException.getMessage());
        }

        log.error("EQP 관리 명령 처리 중 예기치 못한 오류가 발생했습니다.", exception);
        return EqpCommandResult.error(
                INTERNAL_ERROR_STATUS,
                "INTERNAL_ERROR",
                "요청 처리 중 내부 오류가 발생했습니다."
        );
    }

    private String normalizeText(final String value) {
        if (value == null) {
            return null;
        }
        final String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized.toUpperCase(Locale.ROOT);
    }

    /**
     * 서비스 내부 표준 오류 타입입니다.
     *
     * @param statusCode HTTP 상태 코드
     * @param errorCode 오류 코드
     * @param message 오류 메시지
     * @param cause 원인 예외
     */
    private static final class CommandFailureException extends Exception {

        private final int statusCode;
        private final String errorCode;

        private CommandFailureException(
                final int statusCode,
                final String errorCode,
                final String message,
                final Throwable cause
        ) {
            super(message, cause);
            this.statusCode = statusCode;
            this.errorCode = errorCode;
        }

        private int statusCode() {
            return statusCode;
        }

        private String errorCode() {
            return errorCode;
        }
    }
}
