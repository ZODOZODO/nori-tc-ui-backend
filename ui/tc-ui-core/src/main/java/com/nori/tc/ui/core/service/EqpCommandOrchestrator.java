package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.eqp.EqpCommandResult;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.port.db.ModelCrudPort;
import com.nori.tc.ui.domain.common.model.UiModelStatus;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.model.UiModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * EQP create/update/delete 오케스트레이션을 분리합니다.
 */
@Service
public class EqpCommandOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(EqpCommandOrchestrator.class);

    private static final int BAD_REQUEST_STATUS = 400;
    private static final int NOT_FOUND_STATUS = 404;
    private static final int CONFLICT_STATUS = 409;
    private static final int INTERNAL_ERROR_STATUS = 500;

    private final EqpPersistenceService persistenceService;
    private final EqpRuntimeSyncCoordinator runtimeSyncCoordinator;
    private final EqpCompensationCoordinator compensationCoordinator;
    private final ModelCrudPort modelCrudPort;

    public EqpCommandOrchestrator(
            final EqpPersistenceService persistenceService,
            final EqpRuntimeSyncCoordinator runtimeSyncCoordinator,
            final EqpCompensationCoordinator compensationCoordinator,
            final ModelCrudPort modelCrudPort
    ) {
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService is null");
        this.runtimeSyncCoordinator = Objects.requireNonNull(runtimeSyncCoordinator, "runtimeSyncCoordinator is null");
        this.compensationCoordinator = Objects.requireNonNull(compensationCoordinator, "compensationCoordinator is null");
        this.modelCrudPort = Objects.requireNonNull(modelCrudPort, "modelCrudPort is null");
    }

    public EqpCommandResult create(
            final EqpManagementCommand.Create command,
            final long timeoutMs
    ) {
        EqpManagementSnapshot createdSnapshot = null;

        try {
            validateCreateCommand(command);
            validateModelBinding(command.interfaceType(), command.isDev(), command.modelVersionKey());

            createdSnapshot = persistenceService.create(command);
            runtimeSyncCoordinator.syncCreate(createdSnapshot, timeoutMs);

            if (requiresJarReload(null, createdSnapshot)) {
                runtimeSyncCoordinator.reloadJar(createdSnapshot, timeoutMs);
            }

            return EqpCommandResult.ok();
        } catch (Exception exception) {
            if (createdSnapshot != null) {
                compensationCoordinator.compensateCreateFailure(createdSnapshot, timeoutMs);
            }
            return toCommandResult(exception);
        }
    }

    public EqpCommandResult update(
            final String eqpId,
            final EqpManagementCommand.Update command,
            final long timeoutMs
    ) {
        validateEqpId(eqpId);

        final Optional<EqpManagementSnapshot> existingOptional = persistenceService.findSnapshotByEqpId(eqpId);
        if (existingOptional.isEmpty()) {
            return EqpCommandResult.error(NOT_FOUND_STATUS, "NOT_FOUND", "설비를 찾을 수 없습니다.");
        }

        final EqpManagementSnapshot existingSnapshot = existingOptional.get();
        boolean persisted = false;

        try {
            validateUpdateCommand(command, existingSnapshot);
            validateModelBinding(existingSnapshot.eqp().commInterface(), command.isDev(), command.modelVersionKey());

            final EqpManagementSnapshot updatedSnapshot = persistenceService.update(eqpId, command);
            persisted = true;
            runtimeSyncCoordinator.syncUpdate(existingSnapshot, updatedSnapshot, timeoutMs);

            if (requiresJarReload(existingSnapshot, updatedSnapshot)) {
                runtimeSyncCoordinator.reloadJar(updatedSnapshot, timeoutMs);
            }

            return EqpCommandResult.ok();
        } catch (Exception exception) {
            if (persisted) {
                compensationCoordinator.compensateUpdateFailure(existingSnapshot, timeoutMs, hasAnyJar(existingSnapshot));
            }
            return toCommandResult(exception);
        }
    }

    public EqpCommandResult delete(
            final String eqpId,
            final long timeoutMs
    ) {
        validateEqpId(eqpId);

        final Optional<EqpManagementSnapshot> existingOptional = persistenceService.findSnapshotByEqpId(eqpId);
        if (existingOptional.isEmpty()) {
            return EqpCommandResult.error(NOT_FOUND_STATUS, "NOT_FOUND", "설비를 찾을 수 없습니다.");
        }

        final EqpManagementSnapshot snapshot = existingOptional.get();
        boolean deletedFromDb = false;

        try {
            if (!runtimeSyncCoordinator.isAlreadyStopped(snapshot)) {
                runtimeSyncCoordinator.endLifecycle(snapshot, timeoutMs);
            }

            persistenceService.delete(eqpId);
            deletedFromDb = true;
            runtimeSyncCoordinator.syncDelete(snapshot, timeoutMs);

            return EqpCommandResult.ok();
        } catch (Exception exception) {
            if (deletedFromDb) {
                compensationCoordinator.compensateDeleteFailure(snapshot, timeoutMs, hasAnyJar(snapshot));
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
        final Long beforeGateway = before == null || before.eqp() == null ? null : before.eqp().gatewayJarKey();
        final Long afterGateway = after == null || after.eqp() == null ? null : after.eqp().gatewayJarKey();
        final Long beforeBusiness = before == null || before.eqp() == null ? null : before.eqp().businessJarKey();
        final Long afterBusiness = after == null || after.eqp() == null ? null : after.eqp().businessJarKey();
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
