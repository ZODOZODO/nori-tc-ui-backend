package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.model.AsyncResultEntry;
import com.nori.tc.ui.core.model.AsyncStatus;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.model.UiCommandMessage;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.model.UiEquipmentProfileSnapshot;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import com.nori.tc.ui.core.port.messaging.UiBusinessEventPublishPort;
import com.nori.tc.ui.core.port.messaging.UiGatewayEventPublishPort;
import com.nori.tc.ui.core.port.redis.AsyncResultStorePort;
import com.nori.tc.ui.core.registry.DualResponseRegistry;
import com.nori.tc.ui.core.registry.UiDualTaskFinalResult;
import com.nori.tc.ui.domain.common.eqp.UiEqpState;
import com.nori.tc.ui.domain.eqp.UiEqpParam;
import com.nori.tc.ui.domain.eqp.UiEqpPortStatus;
import com.nori.tc.ui.domain.task.UiTaskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * EQP 관리의 runtime 동기화, JAR 반영, 복구 보조 책임을 담당합니다.
 */
@Service
public final class EqpManagementRuntimeSupport {

    private static final Logger log = LoggerFactory.getLogger(EqpManagementRuntimeSupport.class);

    private static final int CONFLICT_STATUS = 409;
    private static final int INTERNAL_ERROR_STATUS = 500;
    private static final int TIMEOUT_STATUS = 504;
    private static final long ASYNC_POLL_INTERVAL_MS = 100L;
    private static final String UI_SOURCE = "TC-UI-APP";

    private final EqpCrudPort eqpCrudPort;
    private final DualResponseRegistry dualResponseRegistry;
    private final UiGatewayEventPublishPort gatewayEventPublishPort;
    private final UiBusinessEventPublishPort businessEventPublishPort;
    private final AsyncResultStorePort asyncResultStorePort;

    EqpManagementRuntimeSupport(
            final EqpCrudPort eqpCrudPort,
            final DualResponseRegistry dualResponseRegistry,
            final UiGatewayEventPublishPort gatewayEventPublishPort,
            final UiBusinessEventPublishPort businessEventPublishPort,
            final AsyncResultStorePort asyncResultStorePort
    ) {
        this.eqpCrudPort = eqpCrudPort;
        this.dualResponseRegistry = dualResponseRegistry;
        this.gatewayEventPublishPort = gatewayEventPublishPort;
        this.businessEventPublishPort = businessEventPublishPort;
        this.asyncResultStorePort = asyncResultStorePort;
    }

    void awaitDualSuccess(
            final UiCommandEventType eventType,
            final EqpManagementSnapshot snapshot,
            final long timeoutMs
    ) throws Exception {
        final String traceId = UUID.randomUUID().toString();
        final String eqpId = snapshot.eqp().eqpId();
        final String interfaceType = snapshot.eqp().commInterface().name();
        final JsonNode uiMessage = buildUiMessage(snapshot.eqp().modelVersionKey());
        final UiEquipmentProfileSnapshot equipmentProfile =
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
            throw new EqpManagementCommandFailureException(
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
                throw new EqpManagementCommandFailureException(
                        INTERNAL_ERROR_STATUS,
                        errorCode,
                        errorMessage,
                        null
                );
            }
        } catch (CompletionException completionException) {
            final Throwable cause = completionException.getCause();
            if (cause instanceof TimeoutException) {
                throw new EqpManagementCommandFailureException(
                        TIMEOUT_STATUS,
                        "TIMEOUT",
                        "Gateway/Business 응답 대기 시간이 초과되었습니다.",
                        cause
                );
            }
            if (cause instanceof CancellationException) {
                throw new EqpManagementCommandFailureException(
                        INTERNAL_ERROR_STATUS,
                        "PUBLISH_FAILED",
                        "Kafka 발행 중 오류가 발생했습니다.",
                        cause
                );
            }
            if (cause instanceof EqpManagementCommandFailureException commandFailureException) {
                throw commandFailureException;
            }

            throw new EqpManagementCommandFailureException(
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

    void awaitLifecycleSuccess(
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
            throw new EqpManagementCommandFailureException(
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

                    throw new EqpManagementCommandFailureException(
                            CONFLICT_STATUS,
                            reply == null ? "EQP_END_FAILED" : reply.errorCode(),
                            reply == null ? "설비 종료에 실패했습니다." : reply.errorMsg(),
                            null
                    );
                }

                if (entry.status() == AsyncStatus.TIMEOUT) {
                    throw new EqpManagementCommandFailureException(
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
        throw new EqpManagementCommandFailureException(
                TIMEOUT_STATUS,
                "TIMEOUT",
                "설비 종료 응답 대기 시간이 초과되었습니다.",
                null
        );
    }

    void awaitJarReloadSuccess(final EqpManagementSnapshot snapshot, final long timeoutMs) throws Exception {
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
            throw new EqpManagementCommandFailureException(
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
                throw new EqpManagementCommandFailureException(
                        INTERNAL_ERROR_STATUS,
                        errorCode,
                        errorMessage,
                        null
                );
            }
        } catch (CompletionException completionException) {
            final Throwable cause = completionException.getCause();
            if (cause instanceof TimeoutException) {
                throw new EqpManagementCommandFailureException(
                        TIMEOUT_STATUS,
                        "TIMEOUT",
                        "Gateway/Business 응답 대기 시간이 초과되었습니다.",
                        cause
                );
            }
            if (cause instanceof CancellationException) {
                throw new EqpManagementCommandFailureException(
                        INTERNAL_ERROR_STATUS,
                        "PUBLISH_FAILED",
                        "Kafka 발행 중 오류가 발생했습니다.",
                        cause
                );
            }
            if (cause instanceof EqpManagementCommandFailureException commandFailureException) {
                throw commandFailureException;
            }

            throw new EqpManagementCommandFailureException(
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

    UiEquipmentProfileSnapshot toEquipmentProfile(final EqpManagementSnapshot snapshot) {
        final OffsetDateTime updatedAt = snapshot.eqp().updatedAt() == null
                ? OffsetDateTime.now()
                : snapshot.eqp().updatedAt();

        final UiEquipmentProfileSnapshot.HsmsSettingsSnapshot hsmsSettings =
                snapshot.hsms() == null ? null : new UiEquipmentProfileSnapshot.HsmsSettingsSnapshot(
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

        final UiEquipmentProfileSnapshot.SocketSettingsSnapshot socketSettings =
                snapshot.socket() == null ? null : new UiEquipmentProfileSnapshot.SocketSettingsSnapshot(
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

        final UiEquipmentProfileSnapshot.CurrentStateSnapshot currentStateSnapshot =
                snapshot.runtimeState() == null ? null : new UiEquipmentProfileSnapshot.CurrentStateSnapshot(
                        snapshot.runtimeState().controlState() == null ? null : snapshot.runtimeState().controlState().name(),
                        snapshot.runtimeState().eqpState() == null ? null : snapshot.runtimeState().eqpState().name(),
                        snapshot.runtimeState().sinceAt() == null ? null : snapshot.runtimeState().sinceAt().toString(),
                        snapshot.runtimeState().reasonCode(),
                        snapshot.runtimeState().reasonDetail(),
                        snapshot.runtimeState().updatedAt() == null ? null : snapshot.runtimeState().updatedAt().toString()
                );

        final List<UiEquipmentProfileSnapshot.PortStatusSnapshot> portStatuses = snapshot.portStatuses().stream()
                .map(this::toPortStatusSnapshot)
                .toList();
        final List<UiEquipmentProfileSnapshot.ParamSnapshot> params = snapshot.params().stream()
                .map(this::toParamSnapshot)
                .toList();

        final UiEquipmentProfileSnapshot.LogPolicySnapshot logPolicy =
                snapshot.logPolicy() == null ? null : new UiEquipmentProfileSnapshot.LogPolicySnapshot(
                        snapshot.logPolicy().logLevel() == null ? null : snapshot.logPolicy().logLevel().name(),
                        snapshot.logPolicy().logRetentionDays(),
                        snapshot.logPolicy().logPath(),
                        snapshot.logPolicy().updatedAt() == null ? null : snapshot.logPolicy().updatedAt().toString()
                );

        return new UiEquipmentProfileSnapshot(
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

    EqpRepairAttemptResult tryRuntimeDelete(final EqpManagementSnapshot snapshot, final long timeoutMs) {
        try {
            awaitDualSuccess(UiCommandEventType.EQP_DELETE, snapshot, timeoutMs);
            return EqpRepairAttemptResult.successResult();
        } catch (Exception exception) {
            return failure("RUNTIME_DELETE_FAILED", "EQP create 보상용 runtime delete 실패", snapshot.eqp().eqpId(), exception);
        }
    }

    EqpRepairAttemptResult tryRuntimeUpdate(final EqpManagementSnapshot snapshot, final long timeoutMs) {
        try {
            awaitDualSuccess(UiCommandEventType.EQP_UPDATE, snapshot, timeoutMs);
            return EqpRepairAttemptResult.successResult();
        } catch (Exception exception) {
            return failure("RUNTIME_UPDATE_FAILED", "EQP update 보상용 runtime sync 실패", snapshot.eqp().eqpId(), exception);
        }
    }

    EqpRepairAttemptResult tryRuntimeCreate(final EqpManagementSnapshot snapshot, final long timeoutMs) {
        try {
            awaitDualSuccess(UiCommandEventType.EQP_CREATE, snapshot, timeoutMs);
            return EqpRepairAttemptResult.successResult();
        } catch (Exception exception) {
            return failure("RUNTIME_CREATE_FAILED", "EQP delete 보상용 runtime recreate 실패", snapshot.eqp().eqpId(), exception);
        }
    }

    EqpRepairAttemptResult tryJarReload(final EqpManagementSnapshot snapshot, final long timeoutMs) {
        try {
            awaitJarReloadSuccess(snapshot, timeoutMs);
            return EqpRepairAttemptResult.successResult();
        } catch (Exception exception) {
            return failure("JAR_RELOAD_FAILED", "EQP jar reload 보상 실패", snapshot.eqp().eqpId(), exception);
        }
    }

    EqpRepairAttemptResult tryDelete(final String eqpId) {
        try {
            eqpCrudPort.delete(eqpId);
            return EqpRepairAttemptResult.successResult();
        } catch (Exception exception) {
            return failure("DB_DELETE_FAILED", "EQP create 보상용 DB delete 실패", eqpId, exception);
        }
    }

    EqpRepairAttemptResult tryRestore(final EqpManagementSnapshot snapshot) {
        try {
            eqpCrudPort.restore(snapshot);
            return EqpRepairAttemptResult.successResult();
        } catch (Exception exception) {
            return failure("DB_RESTORE_FAILED", "EQP 스냅샷 복구 실패", snapshot.eqp().eqpId(), exception);
        }
    }

    boolean isAlreadyStopped(final EqpManagementSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }

        final boolean eqpStateDown = snapshot.runtimeState() != null
                && snapshot.runtimeState().eqpState() == UiEqpState.DOWN;
        final boolean disconnected = normalizeText(snapshot.connectionState())
                .filter("DISCONNECTED"::equalsIgnoreCase)
                .isPresent();

        return eqpStateDown || disconnected;
    }

    private JsonNode buildUiMessage(final long modelVersionKey) {
        final ObjectNode modelInfo = JsonNodeFactory.instance.objectNode();
        modelInfo.put("modelVersionKey", modelVersionKey);

        final ObjectNode uiMessage = JsonNodeFactory.instance.objectNode();
        uiMessage.set("modelInfo", modelInfo);
        return uiMessage;
    }

    private UiEquipmentProfileSnapshot.PortStatusSnapshot toPortStatusSnapshot(final UiEqpPortStatus status) {
        return new UiEquipmentProfileSnapshot.PortStatusSnapshot(
                status.portId(),
                status.portType() == null ? null : status.portType().name(),
                status.portState(),
                status.carrierId(),
                status.carrierType() == null ? null : status.carrierType().name(),
                status.carrierState() == null ? null : status.carrierState().name(),
                status.updatedAt() == null ? null : status.updatedAt().toString()
        );
    }

    private UiEquipmentProfileSnapshot.ParamSnapshot toParamSnapshot(final UiEqpParam param) {
        return new UiEquipmentProfileSnapshot.ParamSnapshot(
                param.eqpParamKey(),
                param.paramName(),
                param.paramVersion(),
                param.paramValue(),
                param.updatedAt() == null ? null : param.updatedAt().toString()
        );
    }

    private Optional<String> normalizeText(final String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.trim().toUpperCase());
    }

    private EqpRepairAttemptResult failure(
            final String errorCode,
            final String actionDescription,
            final String eqpId,
            final Exception exception
    ) {
        final String errorMessage = exception.getMessage() == null
                ? actionDescription + " 중 내부 오류가 발생했습니다."
                : exception.getMessage();
        log.error("{} . eqpId={}", actionDescription, eqpId, exception);
        return EqpRepairAttemptResult.failureResult(errorCode, errorMessage);
    }
}
