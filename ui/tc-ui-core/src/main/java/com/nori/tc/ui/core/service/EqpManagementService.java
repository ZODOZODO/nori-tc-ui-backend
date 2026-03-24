package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.eqp.EqpCommandResult;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementOptions;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import com.nori.tc.ui.core.port.db.EqpManageQueryPort;
import com.nori.tc.ui.core.port.db.EqpOptionsQueryPort;
import com.nori.tc.ui.core.port.db.ModelCrudPort;
import com.nori.tc.ui.core.port.messaging.UiBusinessEventPublishPort;
import com.nori.tc.ui.core.port.messaging.UiGatewayEventPublishPort;
import com.nori.tc.ui.core.port.redis.AsyncResultStorePort;
import com.nori.tc.ui.core.registry.DualResponseRegistry;
import com.nori.tc.ui.domain.common.model.UiModelStatus;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.model.UiModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

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

    private final EqpCrudPort eqpCrudPort;
    private final EqpManageQueryPort eqpManageQueryPort;
    private final EqpOptionsQueryPort eqpOptionsQueryPort;
    private final ModelCrudPort modelCrudPort;
    private final DualResponseRegistry dualResponseRegistry;
    private final UiGatewayEventPublishPort gatewayEventPublishPort;
    private final UiBusinessEventPublishPort businessEventPublishPort;
    private final AsyncResultStorePort asyncResultStorePort;
    private final Executor eqpManagementExecutor;
    private final EqpManagementRuntimeSupport runtimeSupport;

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
        this.runtimeSupport = new EqpManagementRuntimeSupport(
                this.eqpCrudPort,
                this.dualResponseRegistry,
                this.gatewayEventPublishPort,
                this.businessEventPublishPort,
                this.asyncResultStorePort
        );
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
            runtimeSupport.awaitDualSuccess(UiCommandEventType.EQP_CREATE, createdSnapshot, timeoutMs);

            if (requiresJarReload(null, createdSnapshot)) {
                runtimeSupport.awaitJarReloadSuccess(createdSnapshot, timeoutMs);
            }

            return EqpCommandResult.ok();
        } catch (Exception exception) {
            if (createdSnapshot != null) {
                runtimeSupport.safeRuntimeDelete(createdSnapshot, timeoutMs);
                runtimeSupport.safeDelete(createdSnapshot.eqp().eqpId());
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
            runtimeSupport.awaitDualSuccess(UiCommandEventType.EQP_UPDATE, updatedSnapshot, timeoutMs);

            if (requiresJarReload(existingSnapshot, updatedSnapshot)) {
                runtimeSupport.awaitJarReloadSuccess(updatedSnapshot, timeoutMs);
            }

            return EqpCommandResult.ok();
        } catch (Exception exception) {
            if (persisted) {
                runtimeSupport.safeRestore(existingSnapshot);
                runtimeSupport.safeRuntimeUpdate(existingSnapshot, timeoutMs);
                if (hasAnyJar(existingSnapshot)) {
                    runtimeSupport.safeJarReload(existingSnapshot, timeoutMs);
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
            if (!runtimeSupport.isAlreadyStopped(snapshot)) {
                runtimeSupport.awaitLifecycleSuccess(UiCommandEventType.EQP_END, snapshot, timeoutMs);
            }

            eqpCrudPort.delete(eqpId);
            deletedFromDb = true;
            runtimeSupport.awaitDualSuccess(UiCommandEventType.EQP_DELETE, snapshot, timeoutMs);

            return EqpCommandResult.ok();
        } catch (Exception exception) {
            if (deletedFromDb) {
                runtimeSupport.safeRestore(snapshot);
                runtimeSupport.safeRuntimeCreate(snapshot, timeoutMs);
                if (hasAnyJar(snapshot)) {
                    runtimeSupport.safeJarReload(snapshot, timeoutMs);
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
            final UiProtocolType interfaceType,
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

        if (interfaceType == UiProtocolType.SECS && hsmsSettings == null) {
            throw new UiBadRequestException("SECS 설비는 hsmsSettings가 필요합니다.");
        }
        if (interfaceType == UiProtocolType.SOCKET && socketSettings == null) {
            throw new UiBadRequestException("SOCKET 설비는 socketSettings가 필요합니다.");
        }
    }

    private void validateModelBinding(
            final UiProtocolType eqpInterfaceType,
            final boolean isDev,
            final long modelVersionKey
    ) {
        final Optional<UiModel> modelOptional = modelCrudPort.findByModelVersionKey(modelVersionKey);
        if (modelOptional.isEmpty()) {
            throw new UiBadRequestException("연결할 모델을 찾을 수 없습니다.");
        }

        final UiModel model = modelOptional.get();
        if (model.commInterface() != eqpInterfaceType) {
            throw new UiBadRequestException("설비 통신 인터페이스와 모델 통신 인터페이스가 일치하지 않습니다.");
        }

        final UiModelStatus expectedStatus = isDev ? UiModelStatus.DEVELOP : UiModelStatus.OPERATE;
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
        final Long beforeGateway = before == null || before.eqp() == null
                ? null
                : before.eqp().gatewayJarKey();
        final Long afterGateway = after == null || after.eqp() == null
                ? null
                : after.eqp().gatewayJarKey();
        final Long beforeBusiness = before == null || before.eqp() == null
                ? null
                : before.eqp().businessJarKey();
        final Long afterBusiness = after == null || after.eqp() == null
                ? null
                : after.eqp().businessJarKey();

        return !Objects.equals(beforeGateway, afterGateway) || !Objects.equals(beforeBusiness, afterBusiness);
    }

    private boolean hasAnyJar(final EqpManagementSnapshot snapshot) {
        return snapshot != null
                && snapshot.eqp() != null
                && (snapshot.eqp().gatewayJarKey() != null || snapshot.eqp().businessJarKey() != null);
    }

    private EqpCommandResult toCommandResult(final Exception exception) {
        if (exception instanceof UiBadRequestException uiBadRequestException) {
            return EqpCommandResult.error(BAD_REQUEST_STATUS, "INVALID_REQUEST", uiBadRequestException.getMessage());
        }

        if (exception instanceof EqpManagementCommandFailureException commandFailureException) {
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

}
