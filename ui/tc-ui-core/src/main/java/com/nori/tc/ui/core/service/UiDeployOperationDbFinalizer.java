package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
import com.nori.tc.ui.core.coordination.port.UiOperationDbFinalizationPort;
import com.nori.tc.ui.core.deploy.DeployExecutionPlan;
import com.nori.tc.ui.core.deploy.DeployExecutionTarget;
import com.nori.tc.ui.core.deploy.DeployTargetFinalizationCommand;
import com.nori.tc.ui.core.deploy.DeployTargetStatus;
import com.nori.tc.ui.core.port.db.DeployStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

/**
 * commit 된 revision 을 deploy target 상태로 확정합니다(§13.6/§13.7).
 *
 * <p>여기가 target {@code COMPLETED} 를 쓰는 <b>유일한</b> 지점입니다. step 하나의 로컬 성공을 전체
 * target 의 확정으로 해석하지 않는 것이 §13.6 의 요구이고, 이 클래스는 operation 이 모든 applied
 * proof 를 모은 뒤 finalization 단계에서만 호출됩니다.</p>
 *
 * <p>같은 호출을 반복해도 같은 결과여야 합니다(§13.7) — executor 가 확정 직후 죽으면 인계 Pod 가
 * 같은 확정을 다시 수행합니다. 이미 {@code COMPLETED} 인 target 은 건너뛰고 성공으로 reconcile 합니다.</p>
 */
@Service
public class UiDeployOperationDbFinalizer implements UiOperationDbFinalizationPort {

    private static final Logger log = LoggerFactory.getLogger(UiDeployOperationDbFinalizer.class);

    private final DeployStorePort deployStorePort;
    private final DeployEqpFinalizationService deployEqpFinalizationService;
    private final DeployCommandAssembler commandAssembler;
    private final ObjectMapper objectMapper;

    /**
     * finalizer 를 만듭니다.
     *
     * @param deployStorePort deploy 영속 상태 포트
     * @param deployEqpFinalizationService 배포된 버전 확정 서비스
     * @param commandAssembler deploy 요약 갱신기
     * @param objectMapper 확정 명령 파서
     */
    public UiDeployOperationDbFinalizer(
            final DeployStorePort deployStorePort,
            final DeployEqpFinalizationService deployEqpFinalizationService,
            final DeployCommandAssembler commandAssembler,
            final ObjectMapper objectMapper
    ) {
        this.deployStorePort = Objects.requireNonNull(deployStorePort, "deployStorePort is null");
        this.deployEqpFinalizationService = Objects.requireNonNull(
                deployEqpFinalizationService, "deployEqpFinalizationService is null");
        this.commandAssembler = Objects.requireNonNull(commandAssembler, "commandAssembler is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
    }

    @Override
    public Outcome finalizeToTarget(
            final String operationId,
            final UiOperationManifestView manifest,
            final long desiredRevision
    ) {
        final DeployTargetFinalizationCommand command =
                objectMapper.readValue(manifest.dbCommandJson(), DeployTargetFinalizationCommand.class);
        final Optional<DeployExecutionPlan> loaded =
                deployStorePort.findDeployExecutionPlan(command.deployKey());
        if (loaded.isEmpty()) {
            log.error("DEPLOY_FINALIZE_UNEXPECTED. operationId={}, deployKey={} — 확정할 target 정보가 없습니다.",
                    operationId, command.deployKey());
            return Outcome.UNEXPECTED_STATE;
        }

        boolean changed = false;
        for (final DeployExecutionTarget target : loaded.get().targets()) {
            if (!command.deployTargetKeys().contains(target.target().deployTargetKey())) {
                continue;
            }
            if (target.targetStatus() == DeployTargetStatus.COMPLETED) {
                continue;
            }
            deployEqpFinalizationService.finalizeTargetSuccess(target.target(), command.actor());
            deployStorePort.markTargetCompleted(
                    target.target().deployTargetKey(), OffsetDateTime.now(ZoneOffset.UTC));
            changed = true;
        }

        if (!changed) {
            return Outcome.ALREADY_AT_TARGET;
        }
        commandAssembler.refreshDeployStatus(command.deployKey(), null, null);
        log.info("DEPLOY_FINALIZED. operationId={}, deployKey={}, targetKeys={}, revision={}",
                operationId, command.deployKey(), command.deployTargetKeys(), desiredRevision);
        return Outcome.FINALIZED;
    }
}
