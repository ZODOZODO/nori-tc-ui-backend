package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.port.db.ModelCrudPort;
import com.nori.tc.ui.domain.common.model.UiModelStatus;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.model.UiModel;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * EQP direct scenario 실행 전 입력/모델 바인딩 검증을 담당합니다.
 */
@Component
public class EqpManagementCommandValidator {

    private final ModelCrudPort modelCrudPort;

    public EqpManagementCommandValidator(final ModelCrudPort modelCrudPort) {
        this.modelCrudPort = Objects.requireNonNull(modelCrudPort, "modelCrudPort is null");
    }

    public void validateCreateCommand(final EqpManagementCommand.Create command) {
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

    public void validateUpdateCommand(
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

    public void validateEqpId(final String eqpId) {
        if (eqpId == null || eqpId.isBlank()) {
            throw new UiBadRequestException("eqpId는 비어 있을 수 없습니다.");
        }
    }

    public void validateModelBinding(
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

    public void validateCreateTargetAbsent(final Optional<EqpManagementSnapshot> snapshotOptional, final String eqpId) {
        if (snapshotOptional.isPresent()) {
            throw new UiConflictException("이미 등록된 설비입니다. eqpId=" + eqpId);
        }
    }

    public EqpManagementSnapshot requireExistingSnapshot(
            final Optional<EqpManagementSnapshot> snapshotOptional,
            final String eqpId
    ) {
        return snapshotOptional.orElseThrow(() -> new UiNotFoundException("설비를 찾을 수 없습니다. eqpId=" + eqpId));
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
}
