package com.nori.tc.ui.core.port.db;

import tools.jackson.databind.JsonNode;
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
import java.util.Optional;

/**
 * deploy orchestration 상태를 DB에 저장/조회하는 포트입니다.
 */
public interface DeployStorePort {

    PersistedDeploy createDeploy(DeployPlanCommand command, DeployPlanningResult planningResult, String actor);

    void updateDeployStatus(
            long deployKey,
            DeployStatus deployStatus,
            String lastErrorCode,
            String lastErrorMessage
    );

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

    Optional<DeploySummarySnapshot> findDeploySummary(long deployKey);

    Optional<DeployPlanCommand> findRedeployCommand(long deployKey);

    java.util.List<String> findActiveTraceIds(long deployKey);

    void markTargetsCanceled(
            long deployKey,
            OffsetDateTime finishedAt,
            String lastErrorCode,
            String lastErrorMessage
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
