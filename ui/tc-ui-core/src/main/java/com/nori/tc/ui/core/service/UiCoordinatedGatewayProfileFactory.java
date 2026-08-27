package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
import com.nori.tc.ui.core.coordination.model.UiOperationRequest;
import com.nori.tc.ui.core.coordination.port.UiGatewayCommandProfileFactory;
import com.nori.tc.ui.core.deploy.DeployExecutionPlan;
import com.nori.tc.ui.core.deploy.DeployExecutionTarget;
import com.nori.tc.ui.core.deploy.DeployTargetFinalizationCommand;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.model.UiEquipmentProfileSnapshot;
import com.nori.tc.ui.core.port.db.DeployStorePort;
import com.nori.tc.ui.core.port.db.EqpManageQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.Optional;

/**
 * 원장 manifest 의 목표 상태를 Gateway 명령 payload 로 만듭니다(§12.3/§12.4).
 *
 * <p>목표 상태를 <b>DB 확정 전에</b> 만들어야 합니다 — 조정 프로토콜은 commit 이후에 DB 를 쓰므로
 * PREPARE/APPLY 시점의 {@code tc_eqp} 행은 아직 변경 전 상태입니다. 그래서 변경 전 스냅샷 위에
 * manifest 의 DB 명령을 덮어 in-memory 로 목표 스냅샷을 합성하고(직접 실행 경로의 publish-first 와
 * 같은 합성기), 그 스냅샷을 Gateway 계약 profile 로 조립합니다.</p>
 *
 * <p>EQP_CREATE 는 반대로 <b>임시 행</b>이 출처입니다. Gateway 는 {@code equipmentProfile.eqpKey} 를
 * 양수로 요구하고({@code GatewayUiRuntimeControlSupport}) 적용 단계에서 {@code tc_eqp} 를 참조하는
 * 상태 이력을 남기므로, §16.1 에 따라 PREPARE 전에 만들어 둔 행을 그대로 읽습니다.</p>
 */
public final class UiCoordinatedGatewayProfileFactory implements UiGatewayCommandProfileFactory {

    private static final Logger log = LoggerFactory.getLogger(UiCoordinatedGatewayProfileFactory.class);

    private final EqpManageQueryPort eqpManageQueryPort;
    private final EqpDirectScenarioLocalMutationCoordinator localMutationCoordinator;
    private final DeployCommandAssembler commandAssembler;
    private final DeployStorePort deployStorePort;
    private final ObjectMapper objectMapper;

    /**
     * 팩토리를 만듭니다.
     *
     * @param eqpManageQueryPort 변경 전 설비 스냅샷 조회 포트
     * @param localMutationCoordinator 목표 스냅샷 in-memory 합성기
     * @param commandAssembler Gateway 계약 profile 조립기
     * @param deployStorePort deploy 실행 계획 조회 포트
     * @param objectMapper manifest DB 명령 파서
     */
    public UiCoordinatedGatewayProfileFactory(
            final EqpManageQueryPort eqpManageQueryPort,
            final EqpDirectScenarioLocalMutationCoordinator localMutationCoordinator,
            final DeployCommandAssembler commandAssembler,
            final DeployStorePort deployStorePort,
            final ObjectMapper objectMapper
    ) {
        this.eqpManageQueryPort = Objects.requireNonNull(eqpManageQueryPort, "eqpManageQueryPort is null");
        this.localMutationCoordinator =
                Objects.requireNonNull(localMutationCoordinator, "localMutationCoordinator is null");
        this.commandAssembler = Objects.requireNonNull(commandAssembler, "commandAssembler is null");
        this.deployStorePort = Objects.requireNonNull(deployStorePort, "deployStorePort is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
    }

    @Override
    public UiEquipmentProfileSnapshot profileFor(final UiOperationManifestView manifest) {
        Objects.requireNonNull(manifest, "manifest is null");
        if (!manifest.requiresGatewayParticipant()) {
            return null;
        }
        final String kind = manifest.dbCommandKind();
        if (UiOperationRequest.DB_COMMAND_CREATE.equals(kind)) {
            return createProfileOf(manifest);
        }
        if (UiOperationRequest.DB_COMMAND_UPDATE.equals(kind)) {
            return updateProfileOf(manifest);
        }
        if (UiOperationRequest.DB_COMMAND_DEPLOY_TARGET.equals(kind)) {
            return deployProfileOf(manifest);
        }
        return null;
    }

    /**
     * 설비 생성 operation 의 목표 profile 을 만듭니다(§16.1).
     *
     * <p>생성은 PREPARE 전에 임시 행이 만들어지므로({@code UiEqpOperationDbFinalizer.stageBeforePrepare})
     * 그 행이 곧 목표 상태이고 {@code eqp_key} 도 이미 발급돼 있습니다 — Gateway 가 요구하는 양수
     * {@code eqpKey} 의 출처가 여기입니다.</p>
     */
    private UiEquipmentProfileSnapshot createProfileOf(final UiOperationManifestView manifest) {
        final EqpManagementSnapshot staged =
                eqpManageQueryPort.findManageSnapshotByEqpId(manifest.eqpId()).orElse(null);
        if (staged == null) {
            log.error("UI_GATEWAY_PROFILE_STAGED_ROW_MISSING. eqpId={} — 생성 임시 행이 없어 profile 을 만들 수 없습니다.",
                    manifest.eqpId());
            return null;
        }
        return commandAssembler.buildDirectEquipmentProfile(
                UiCommandEventType.EQP_CREATE, staged, objectMapper.createObjectNode());
    }

    /**
     * 직접 수정 operation 의 목표 profile 을 만듭니다.
     */
    private UiEquipmentProfileSnapshot updateProfileOf(final UiOperationManifestView manifest) {
        final EqpManagementSnapshot before =
                eqpManageQueryPort.findManageSnapshotByEqpId(manifest.eqpId()).orElse(null);
        if (before == null) {
            /*
             * 수정 대상 행이 없으면 목표 상태를 합성할 근거가 없다. 여기서 지어내지 않고 null 을 반환하면
             * Gateway 가 EQP_PROFILE_PAYLOAD_REQUIRED 로 거부하고 operation 이 그 이유로 실패한다 —
             * 잘못된 profile 로 런타임을 덮는 것보다 낫다.
             */
            log.error("UI_GATEWAY_PROFILE_SOURCE_MISSING. eqpId={}, eventType={}",
                    manifest.eqpId(), manifest.eventType());
            return null;
        }
        final EqpManagementCommand.Update command =
                objectMapper.readValue(manifest.dbCommandJson(), EqpManagementCommand.Update.class);
        final EqpManagementSnapshot desired = localMutationCoordinator.synthesizeUpdatedSnapshotInMemory(
                before, command, manifest.actor());
        return commandAssembler.buildDirectEquipmentProfile(
                contextEventTypeOf(manifest), desired, objectMapper.createObjectNode());
    }

    /**
     * deploy operation 의 목표 profile 을 만듭니다.
     *
     * <p>deploy 는 목표 상태를 계획에 영속해 두므로({@code desiredState.mergedUiMessage}) 그 문서가
     * 출처입니다 — step 발행 경로와 같은 문서를 같은 조립기로 읽습니다. PLC 디코드 스펙(groups)만
     * 발행 직전 모델에서 다시 채웁니다(영속 JSON 에는 싣지 않습니다).</p>
     */
    private UiEquipmentProfileSnapshot deployProfileOf(final UiOperationManifestView manifest) {
        final DeployTargetFinalizationCommand command =
                objectMapper.readValue(manifest.dbCommandJson(), DeployTargetFinalizationCommand.class);
        final Optional<DeployExecutionPlan> plan = deployStorePort.findDeployExecutionPlan(command.deployKey());
        if (plan.isEmpty()) {
            log.error("UI_GATEWAY_PROFILE_DEPLOY_PLAN_MISSING. deployKey={}, eqpId={}",
                    command.deployKey(), manifest.eqpId());
            return null;
        }
        final DeployExecutionTarget target = plan.get().targets().stream()
                .filter(candidate -> manifest.eqpId().equals(candidate.target().planDraft().eqpId()))
                .findFirst()
                .orElse(null);
        if (target == null) {
            log.error("UI_GATEWAY_PROFILE_DEPLOY_TARGET_MISSING. deployKey={}, eqpId={}",
                    command.deployKey(), manifest.eqpId());
            return null;
        }
        final JsonNode profileNode = deployProfileNodeOf(target);
        if (profileNode == null) {
            log.error("UI_GATEWAY_PROFILE_DEPLOY_PAYLOAD_MISSING. deployKey={}, eqpId={}",
                    command.deployKey(), manifest.eqpId());
            return null;
        }
        return commandAssembler.injectPlcGroupsIfPresent(
                commandAssembler.parseEquipmentProfile(profileNode), target.target());
    }

    private static JsonNode deployProfileNodeOf(final DeployExecutionTarget target) {
        if (target.target().planDraft().desiredState() == null) {
            return null;
        }
        final JsonNode merged = target.target().planDraft().desiredState().mergedUiMessage();
        if (merged == null) {
            return null;
        }
        final JsonNode profileNode = merged.get("equipmentProfile");
        return profileNode == null || !profileNode.isObject() ? null : profileNode;
    }

    /**
     * profile 조립기가 아는 context 변경 eventType 으로 정합니다.
     *
     * <p>{@code EQP_BUSINESS_JARFILE_UPDATE} 는 Business 쪽 종류이고 Gateway 의 context 변경은
     * {@code EQP_UPDATE} 입니다 — 조립기도 CREATE/UPDATE/MODEL_UPDATE 만 다룹니다.</p>
     */
    private static UiCommandEventType contextEventTypeOf(final UiOperationManifestView manifest) {
        final UiCommandEventType eventType = UiCommandEventType.valueOf(manifest.eventType());
        return eventType == UiCommandEventType.EQP_MODEL_UPDATE
                ? UiCommandEventType.EQP_MODEL_UPDATE
                : UiCommandEventType.EQP_UPDATE;
    }
}
