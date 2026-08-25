package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
import com.nori.tc.ui.core.coordination.model.UiOperationRequest;
import com.nori.tc.ui.core.coordination.port.UiOperationDbFinalizationPort;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.service.EqpPersistenceService;
import com.nori.tc.ui.domain.eqp.UiEqp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.Optional;

/**
 * commit 된 revision 을 업무 DB 에 idempotent 하게 확정합니다(§13.7).
 *
 * <p>확정은 <b>expected old / desired 판정</b>을 먼저 합니다.</p>
 * <ul>
 *   <li>DB 가 이미 목표 상태면 성공으로 reconcile 합니다 — executor crash 후 같은 호출을 반복해도
 *       같은 결과가 나와야 합니다.</li>
 *   <li>DB 가 manifest 가 기록한 <b>이전 상태</b>면 목표 상태로 바꿉니다.</li>
 *   <li>어느 쪽도 아니면 blind overwrite 하지 않고 {@code UNEXPECTED_STATE} 로 보고합니다 — 알 수 없는
 *       상태를 덮으면 무엇이 유실됐는지 사후에 알 수 없습니다.</li>
 * </ul>
 */
public final class UiEqpOperationDbFinalizer implements UiOperationDbFinalizationPort {

    private static final Logger log = LoggerFactory.getLogger(UiEqpOperationDbFinalizer.class);

    private final EqpPersistenceService persistenceService;
    private final ObjectMapper objectMapper;
    private final UiRuntimeDescriptorReader descriptorReader;

    /**
     * finalizer 를 만듭니다.
     *
     * @param persistenceService 설비 DB 영속화 서비스
     * @param objectMapper JSON 파서
     * @param descriptorReader descriptor 해석기
     */
    public UiEqpOperationDbFinalizer(
            final EqpPersistenceService persistenceService,
            final ObjectMapper objectMapper,
            final UiRuntimeDescriptorReader descriptorReader
    ) {
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
        this.descriptorReader = Objects.requireNonNull(descriptorReader, "descriptorReader is null");
    }

    @Override
    public Outcome finalizeToTarget(
            final String operationId,
            final UiOperationManifestView manifest,
            final long desiredRevision
    ) {
        final String eqpId = manifest.eqpId();
        final Optional<EqpManagementSnapshot> current = persistenceService.findSnapshotByEqpId(eqpId);
        final String kind = manifest.dbCommandKind();

        if (kind == null) {
            // DB 변경을 동반하지 않는 operation(예: runtime 파라미터만 바꾸는 경우)이다.
            return Outcome.ALREADY_AT_TARGET;
        }

        return switch (kind) {
            case UiOperationRequest.DB_COMMAND_CREATE -> finalizeCreate(operationId, manifest, current);
            case UiOperationRequest.DB_COMMAND_UPDATE -> finalizeUpdate(operationId, manifest, eqpId, current);
            case UiOperationRequest.DB_COMMAND_DELETE -> finalizeDelete(operationId, eqpId, current);
            default -> {
                log.error("UI_DB_FINALIZE_UNKNOWN_KIND. operationId={}, kind={}", operationId, kind);
                yield Outcome.UNEXPECTED_STATE;
            }
        };
    }

    private Outcome finalizeCreate(
            final String operationId,
            final UiOperationManifestView manifest,
            final Optional<EqpManagementSnapshot> current
    ) {
        final EqpManagementCommand.Create command =
                objectMapper.readValue(manifest.dbCommandJson(), EqpManagementCommand.Create.class);
        if (current.isPresent()) {
            if (matchesTarget(current.get().eqp(), manifest)) {
                return Outcome.ALREADY_AT_TARGET;
            }
            log.error("UI_DB_FINALIZE_UNEXPECTED. operationId={}, eqpId={} — 생성 대상 설비가 이미 다른 상태로 존재합니다.",
                    operationId, command.eqpId());
            return Outcome.UNEXPECTED_STATE;
        }
        persistenceService.create(command);
        return Outcome.FINALIZED;
    }

    private Outcome finalizeUpdate(
            final String operationId,
            final UiOperationManifestView manifest,
            final String eqpId,
            final Optional<EqpManagementSnapshot> current
    ) {
        if (current.isEmpty()) {
            log.error("UI_DB_FINALIZE_UNEXPECTED. operationId={}, eqpId={} — 수정 대상 설비가 없습니다.",
                    operationId, eqpId);
            return Outcome.UNEXPECTED_STATE;
        }
        final UiEqp existing = current.get().eqp();
        if (matchesTarget(existing, manifest)) {
            return Outcome.ALREADY_AT_TARGET;
        }
        if (!matchesPrevious(existing, manifest)) {
            log.error("UI_DB_FINALIZE_UNEXPECTED. operationId={}, eqpId={} — DB 가 예상 이전/목표 어느 상태도 아닙니다.",
                    operationId, eqpId);
            return Outcome.UNEXPECTED_STATE;
        }
        final EqpManagementCommand.Update command =
                objectMapper.readValue(manifest.dbCommandJson(), EqpManagementCommand.Update.class);
        persistenceService.update(eqpId, command);
        return Outcome.FINALIZED;
    }

    private Outcome finalizeDelete(
            final String operationId,
            final String eqpId,
            final Optional<EqpManagementSnapshot> current
    ) {
        if (current.isEmpty()) {
            return Outcome.ALREADY_AT_TARGET;
        }
        persistenceService.delete(eqpId);
        log.info("UI_DB_FINALIZED_DELETE. operationId={}, eqpId={}", operationId, eqpId);
        return Outcome.FINALIZED;
    }

    /**
     * DB 행이 manifest 의 목표 상태와 같은지 판정합니다.
     *
     * <p>descriptor 가 담은 필드만 비교합니다 — descriptor 가 바꾸지 않는 필드까지 비교하면
     * 무관한 변경 때문에 idempotent 재확정이 {@code UNEXPECTED_STATE} 로 오판됩니다.</p>
     */
    private boolean matchesTarget(final UiEqp existing, final UiOperationManifestView manifest) {
        return matchesDescriptor(existing, manifest.descriptorJsonByResourceKey()
                .get(targetResourceKey(manifest)));
    }

    private boolean matchesPrevious(final UiEqp existing, final UiOperationManifestView manifest) {
        final String previousJson = manifest.previousDescriptorJsonByResourceKey().get(targetResourceKey(manifest));
        if (previousJson == null) {
            // 이전 상태를 기록하지 못한 operation 은 old 판정을 할 수 없다 — 덮어쓰지 않는다.
            return false;
        }
        return matchesDescriptor(existing, previousJson);
    }

    private boolean matchesDescriptor(final UiEqp existing, final String descriptorJson) {
        if (descriptorJson == null) {
            return false;
        }
        final UiRuntimeDescriptorReader.DescriptorFields fields = descriptorReader.read(descriptorJson);
        if (fields.modelVersionKey() != null && fields.modelVersionKey() != existing.modelVersionKey()) {
            return false;
        }
        if (fields.routePartition() != null && !fields.routePartition().equals(existing.routePartition())) {
            return false;
        }
        return fields.businessJarKey() == null || fields.businessJarKey().equals(existing.businessJarKey());
    }

    private static String targetResourceKey(final UiOperationManifestView manifest) {
        return UiResourceKeyPlanner.RESOURCE_TYPE_EQUIPMENT + ":" + manifest.eqpId();
    }
}
