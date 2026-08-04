package com.nori.tc.ui.core.port.db;

import tools.jackson.databind.JsonNode;
import com.nori.tc.ui.core.deploy.DeployActiveEqpConflict;
import com.nori.tc.ui.core.deploy.DeployExecutionPlan;
import com.nori.tc.ui.core.deploy.DeployPlanCommand;
import com.nori.tc.ui.core.deploy.DeployPlanningResult;
import com.nori.tc.ui.core.deploy.DeploySnapshotKind;
import com.nori.tc.ui.core.deploy.DeploySnapshotScope;
import com.nori.tc.ui.core.deploy.DeployStatus;
import com.nori.tc.ui.core.deploy.DeployStepStatus;
import com.nori.tc.ui.core.deploy.DeployStoredSnapshot;
import com.nori.tc.ui.core.deploy.DeployHistoryView;
import com.nori.tc.ui.core.deploy.DeploySummarySnapshot;
import com.nori.tc.ui.core.deploy.DeployTargetCounts;
import com.nori.tc.ui.core.deploy.DeployTargetPage;
import com.nori.tc.ui.core.deploy.DeployTargetStatus;
import com.nori.tc.ui.core.deploy.PersistedDeploy;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * deploy orchestration 상태를 DB에 저장/조회하는 포트입니다.
 */
public interface DeployStorePort {

    PersistedDeploy createDeploy(DeployPlanCommand command, DeployPlanningResult planningResult, String actor);

    /**
     * deploy 헤더 상태를 기록합니다.
     *
     * <p>계약: {@code CANCELED} 는 terminal 이므로 이미 CANCELED 인 deploy 는 갱신되지 않습니다.
     * 호출자의 상태 확인(읽기)과 이 기록(쓰기)은 서로 다른 트랜잭션이라, 그 사이에 취소가 커밋되면
     * 가드 없이는 취소된 deploy 가 COMPLETED 로 부활합니다(S08 D-2).</p>
     */
    void updateDeployStatus(
            long deployKey,
            DeployStatus deployStatus,
            boolean pauseRequested,
            String lastErrorCode,
            String lastErrorMessage
    );

    void updatePauseRequested(long deployKey, boolean pauseRequested);

    void markTargetWaitingReply(
            long deployTargetKey,
            int stepOrder,
            String stepCode,
            int attemptNo,
            String traceId,
            OffsetDateTime stepStartedAt,
            OffsetDateTime stepTimeoutAt
    );

    void markTargetCompleted(long deployTargetKey, OffsetDateTime finishedAt);

    void markTargetRollingBack(
            long deployTargetKey,
            int stepOrder,
            String stepCode,
            int attemptNo,
            String traceId,
            String lastErrorCode,
            String lastErrorMessage,
            OffsetDateTime stepStartedAt,
            OffsetDateTime stepTimeoutAt
    );

    void markTargetRolledBack(
            long deployTargetKey,
            String lastErrorCode,
            String lastErrorMessage,
            OffsetDateTime finishedAt
    );

    void markTargetFailed(
            long deployTargetKey,
            int stepOrder,
            String stepCode,
            DeployStepStatus stepStatus,
            String lastErrorCode,
            String lastErrorMessage,
            boolean rollbackRequired,
            OffsetDateTime finishedAt
    );

    void markTargetCompensationFailed(
            long deployTargetKey,
            int stepOrder,
            String stepCode,
            String lastErrorCode,
            String lastErrorMessage,
            OffsetDateTime finishedAt
    );

    void saveTargetSnapshot(
            long deployTargetKey,
            DeploySnapshotScope snapshotScope,
            String stepCode,
            DeploySnapshotKind snapshotKind,
            String sourceOfTruth,
            JsonNode snapshotData
    );

    Optional<DeployStoredSnapshot> findLatestTargetSnapshot(
            long deployTargetKey,
            DeploySnapshotScope snapshotScope,
            String stepCode,
            DeploySnapshotKind snapshotKind
    );

    /**
     * 여러 deploy target 의 최신 스냅샷을 한 번에 조회합니다(polling N+1 완화, R5).
     *
     * @param deployTargetKeys 조회 대상 deploy_target_key 컬렉션
     * @param snapshotScope 스냅샷 scope
     * @param stepCode step code(null 허용)
     * @param snapshotKind 스냅샷 kind
     * @return deployTargetKey → 최신 스냅샷 (없는 target 은 부재)
     */
    Map<Long, DeployStoredSnapshot> findLatestTargetSnapshots(
            Collection<Long> deployTargetKeys,
            DeploySnapshotScope snapshotScope,
            String stepCode,
            DeploySnapshotKind snapshotKind
    );

    Optional<DeploySummarySnapshot> findDeploySummary(long deployKey);

    Optional<DeployExecutionPlan> findDeployExecutionPlan(long deployKey);

    Optional<DeployActiveEqpConflict> findActiveEqpConflict(Collection<String> eqpIds, Long excludedDeployKey);

    Optional<DeployPlanCommand> findRedeployCommand(long deployKey);

    java.util.List<String> findActiveTraceIds(long deployKey);

    /**
     * deploy 취소를 반영합니다 — 미종료 target 의 CANCELED 전환과 헤더 CANCELED 기록이 한 단위입니다.
     *
     * <p>계약: 부분 반영을 남기지 않습니다. 두 쓰기를 분리하면 뒤쪽 실패 시 target 은 전부 CANCELED /
     * 헤더는 RUNNING 으로 남고, pending·running=0 이라 어떤 실행 스레드도 상태를 다시 계산하지 않아
     * RUNNING 에 영구 고착된다(S08 D-4).</p>
     */
    void markDeployCanceled(
            long deployKey,
            OffsetDateTime canceledAt,
            String reasonCode,
            String reasonDetail
    );

    void markTargetReadyForResume(
            long deployTargetKey,
            Integer completedStepOrder,
            String completedStepCode,
            OffsetDateTime updatedAt
    );

    boolean deleteDeploy(long deployKey);

    PagedResponse<DeployHistoryView> findDeployHistory(
            String modelName,
            String requestedBy,
            OffsetDateTime fromCreatedAt,
            OffsetDateTime toCreatedAtExclusive,
            boolean terminalOnly,
            UiPageRequest pageRequest
    );

    DeployTargetCounts summarizeTargets(long deployKey);

    DeployTargetPage findDeployTargets(
            long deployKey,
            int page,
            int size,
            DeployTargetStatus statusFilter,
            String eqpIdFilter
    );
}
