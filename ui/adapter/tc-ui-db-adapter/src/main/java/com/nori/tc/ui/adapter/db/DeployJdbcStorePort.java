package com.nori.tc.ui.adapter.db;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ObjectMapper;
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
import com.nori.tc.ui.core.deploy.DeployTargetSnapshot;
import com.nori.tc.ui.core.deploy.DeployTargetStatus;
import com.nori.tc.ui.core.deploy.PersistedDeploy;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.port.db.DeployStorePort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * deploy orchestration 상태를 PostgreSQL에 저장/조회하는 JDBC 어댑터입니다.
 *
 * <p>plan/state/snapshot을 분리한 DDL 구조를 그대로 사용하여 hot update 시
 * 큰 JSONB를 반복 갱신하지 않도록 설계합니다.</p>
 */
@Repository
public class DeployJdbcStorePort implements DeployStorePort {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final DeployJdbcStoreSupport support;

    public DeployJdbcStorePort(
            final NamedParameterJdbcTemplate jdbcTemplate,
            final ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate is null");
        this.support = new DeployJdbcStoreSupport(jdbcTemplate, objectMapper);
    }

    @Override
    @Transactional
    public PersistedDeploy createDeploy(
            final DeployPlanCommand command,
            final DeployPlanningResult planningResult,
            final String actor
    ) {
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        final String normalizedActor = support.normalizeActor(actor);
        final int totalCount = planningResult.targets().size();
        final int noChangeCount = (int) planningResult.targets().stream()
                .filter(target -> target.noChange())
                .count();

        final long deployKey = support.insertDeploy(
                command,
                planningResult,
                totalCount,
                noChangeCount,
                normalizedActor,
                now
        );

        final List<PersistedDeployTarget> persistedTargets = new ArrayList<>();
        int targetOrder = 1;
        for (var targetPlan : planningResult.targets()) {
            final long deployTargetKey = support.insertTargetPlan(
                    deployKey,
                    targetOrder,
                    targetPlan,
                    normalizedActor,
                    now
            );
            support.insertTargetState(deployTargetKey, targetPlan.noChange(), now);
            persistedTargets.add(new PersistedDeployTarget(deployTargetKey, targetOrder, targetPlan));
            targetOrder++;
        }

        return new PersistedDeploy(deployKey, DeployStatus.PLANNING, List.copyOf(persistedTargets));
    }

    @Override
    @Transactional
    public void updateDeployStatus(
            final long deployKey,
            final DeployStatus deployStatus,
            final String lastErrorCode,
            final String lastErrorMessage
    ) {
        final DeployTargetCounts counts = summarizeTargets(deployKey);
        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("deployKey", deployKey)
                .addValue("deployStatus", deployStatus.name())
                .addValue("targetTotalCount", counts.total())
                .addValue("targetNoChangeCount", counts.noChange())
                .addValue("targetCompletedCount", counts.completed())
                .addValue("targetFailedCount", counts.failed())
                .addValue("targetRolledBackCount", counts.rolledBack())
                .addValue("lastErrorCode", support.normalizeOptionalText(lastErrorCode))
                .addValue("lastErrorMessage", support.normalizeOptionalText(lastErrorMessage));

        jdbcTemplate.update("""
                        UPDATE tc_deploy
                           SET deploy_status = :deployStatus,
                               target_total_count = :targetTotalCount,
                               target_no_change_count = :targetNoChangeCount,
                               target_completed_count = :targetCompletedCount,
                               target_failed_count = :targetFailedCount,
                               target_rolled_back_count = :targetRolledBackCount,
                               last_error_code = COALESCE(:lastErrorCode, last_error_code),
                               last_error_message = COALESCE(:lastErrorMessage, last_error_message),
                               updated_at = CURRENT_TIMESTAMP
                         WHERE deploy_key = :deployKey
                        """,
                parameters
        );
    }

    @Override
    @Transactional
    public void markTargetWaitingReply(
            final long deployTargetKey,
            final int stepOrder,
            final String stepCode,
            final int attemptNo,
            final String traceId,
            final OffsetDateTime stepStartedAt,
            final OffsetDateTime stepTimeoutAt
    ) {
        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("deployTargetKey", deployTargetKey)
                .addValue("stepOrder", stepOrder)
                .addValue("stepCode", support.normalizeOptionalText(stepCode))
                .addValue("attemptNo", attemptNo)
                .addValue("traceId", support.normalizeOptionalText(traceId))
                .addValue("stepStartedAt", stepStartedAt)
                .addValue("stepTimeoutAt", stepTimeoutAt);

        jdbcTemplate.update("""
                        UPDATE tc_deploy_target_state
                           SET target_status = :targetStatus,
                               current_step_order = :stepOrder,
                               current_step_code = :stepCode,
                               current_step_status = :stepStatus,
                               current_step_attempt_no = :attemptNo,
                               current_trace_id = :traceId,
                               current_step_started_at = :stepStartedAt,
                               current_step_timeout_at = :stepTimeoutAt,
                               started_at = COALESCE(started_at, :stepStartedAt),
                               finished_at = NULL,
                               updated_at = CURRENT_TIMESTAMP,
                               row_version = row_version + 1
                         WHERE deploy_target_key = :deployTargetKey
                        """,
                parameters
                        .addValue("targetStatus", DeployTargetStatus.RUNNING.name())
                        .addValue("stepStatus", DeployStepStatus.WAITING_REPLY.name())
        );
    }

    @Override
    @Transactional
    public void markTargetCompleted(final long deployTargetKey, final OffsetDateTime finishedAt) {
        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("deployTargetKey", deployTargetKey)
                .addValue("finishedAt", finishedAt);

        jdbcTemplate.update("""
                        UPDATE tc_deploy_target_state
                           SET target_status = :targetStatus,
                               current_step_order = NULL,
                               current_step_code = NULL,
                               current_step_status = NULL,
                               current_step_attempt_no = 0,
                               current_trace_id = NULL,
                               current_step_started_at = NULL,
                               current_step_timeout_at = NULL,
                               rollback_required = FALSE,
                               last_error_code = NULL,
                               last_error_message = NULL,
                               finished_at = :finishedAt,
                               updated_at = CURRENT_TIMESTAMP,
                               row_version = row_version + 1
                         WHERE deploy_target_key = :deployTargetKey
                        """,
                parameters.addValue("targetStatus", DeployTargetStatus.COMPLETED.name())
        );
    }

    @Override
    @Transactional
    public void markTargetRollingBack(
            final long deployTargetKey,
            final int stepOrder,
            final String stepCode,
            final int attemptNo,
            final String traceId,
            final String lastErrorCode,
            final String lastErrorMessage,
            final OffsetDateTime stepStartedAt,
            final OffsetDateTime stepTimeoutAt
    ) {
        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("deployTargetKey", deployTargetKey)
                .addValue("stepOrder", stepOrder <= 0 ? null : stepOrder)
                .addValue("stepCode", support.normalizeOptionalText(stepCode))
                .addValue("attemptNo", attemptNo)
                .addValue("traceId", support.normalizeOptionalText(traceId))
                .addValue("lastErrorCode", support.normalizeOptionalText(lastErrorCode))
                .addValue("lastErrorMessage", support.normalizeOptionalText(lastErrorMessage))
                .addValue("stepStartedAt", stepStartedAt)
                .addValue("stepTimeoutAt", stepTimeoutAt);

        jdbcTemplate.update("""
                        UPDATE tc_deploy_target_state
                           SET target_status = :targetStatus,
                               current_step_order = :stepOrder,
                               current_step_code = :stepCode,
                               current_step_status = :stepStatus,
                               current_step_attempt_no = :attemptNo,
                               current_trace_id = :traceId,
                               current_step_started_at = :stepStartedAt,
                               current_step_timeout_at = :stepTimeoutAt,
                               rollback_required = TRUE,
                               last_error_code = :lastErrorCode,
                               last_error_message = :lastErrorMessage,
                               finished_at = NULL,
                               updated_at = CURRENT_TIMESTAMP,
                               row_version = row_version + 1
                         WHERE deploy_target_key = :deployTargetKey
                        """,
                parameters
                        .addValue("targetStatus", DeployTargetStatus.ROLLING_BACK.name())
                        .addValue("stepStatus", DeployStepStatus.ROLLING_BACK.name())
        );
    }

    @Override
    @Transactional
    public void markTargetRolledBack(
            final long deployTargetKey,
            final String lastErrorCode,
            final String lastErrorMessage,
            final OffsetDateTime finishedAt
    ) {
        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("deployTargetKey", deployTargetKey)
                .addValue("lastErrorCode", support.normalizeOptionalText(lastErrorCode))
                .addValue("lastErrorMessage", support.normalizeOptionalText(lastErrorMessage))
                .addValue("finishedAt", finishedAt);

        jdbcTemplate.update("""
                        UPDATE tc_deploy_target_state
                           SET target_status = :targetStatus,
                               current_step_order = NULL,
                               current_step_code = NULL,
                               current_step_status = NULL,
                               current_step_attempt_no = 0,
                               current_trace_id = NULL,
                               current_step_started_at = NULL,
                               current_step_timeout_at = NULL,
                               rollback_required = FALSE,
                               last_error_code = :lastErrorCode,
                               last_error_message = :lastErrorMessage,
                               finished_at = :finishedAt,
                               updated_at = CURRENT_TIMESTAMP,
                               row_version = row_version + 1
                         WHERE deploy_target_key = :deployTargetKey
                        """,
                parameters.addValue("targetStatus", DeployTargetStatus.ROLLED_BACK.name())
        );
    }

    @Override
    @Transactional
    public void markTargetFailed(
            final long deployTargetKey,
            final int stepOrder,
            final String stepCode,
            final DeployStepStatus stepStatus,
            final String lastErrorCode,
            final String lastErrorMessage,
            final boolean rollbackRequired,
            final OffsetDateTime finishedAt
    ) {
        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("deployTargetKey", deployTargetKey)
                .addValue("stepOrder", stepOrder <= 0 ? null : stepOrder)
                .addValue("stepCode", support.normalizeOptionalText(stepCode))
                .addValue("stepStatus", stepStatus == null ? DeployStepStatus.FAILED.name() : stepStatus.name())
                .addValue("lastErrorCode", support.normalizeOptionalText(lastErrorCode))
                .addValue("lastErrorMessage", support.normalizeOptionalText(lastErrorMessage))
                .addValue("rollbackRequired", rollbackRequired)
                .addValue("finishedAt", finishedAt);

        jdbcTemplate.update("""
                        UPDATE tc_deploy_target_state
                           SET target_status = :targetStatus,
                               current_step_order = :stepOrder,
                               current_step_code = :stepCode,
                               current_step_status = :stepStatus,
                               current_trace_id = NULL,
                               current_step_timeout_at = NULL,
                               rollback_required = :rollbackRequired,
                               last_error_code = :lastErrorCode,
                               last_error_message = :lastErrorMessage,
                               finished_at = :finishedAt,
                               updated_at = CURRENT_TIMESTAMP,
                               row_version = row_version + 1
                         WHERE deploy_target_key = :deployTargetKey
                        """,
                parameters.addValue("targetStatus", DeployTargetStatus.FAILED.name())
        );
    }

    @Override
    @Transactional
    public void markTargetCompensationFailed(
            final long deployTargetKey,
            final int stepOrder,
            final String stepCode,
            final String lastErrorCode,
            final String lastErrorMessage,
            final OffsetDateTime finishedAt
    ) {
        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("deployTargetKey", deployTargetKey)
                .addValue("stepOrder", stepOrder <= 0 ? null : stepOrder)
                .addValue("stepCode", support.normalizeOptionalText(stepCode))
                .addValue("lastErrorCode", support.normalizeOptionalText(lastErrorCode))
                .addValue("lastErrorMessage", support.normalizeOptionalText(lastErrorMessage))
                .addValue("finishedAt", finishedAt);

        jdbcTemplate.update("""
                        UPDATE tc_deploy_target_state
                           SET target_status = :targetStatus,
                               current_step_order = :stepOrder,
                               current_step_code = :stepCode,
                               current_step_status = :stepStatus,
                               current_trace_id = NULL,
                               current_step_timeout_at = NULL,
                               rollback_required = TRUE,
                               last_error_code = :lastErrorCode,
                               last_error_message = :lastErrorMessage,
                               finished_at = :finishedAt,
                               updated_at = CURRENT_TIMESTAMP,
                               row_version = row_version + 1
                         WHERE deploy_target_key = :deployTargetKey
                        """,
                parameters
                        .addValue("targetStatus", DeployTargetStatus.COMPENSATION_FAILED.name())
                        .addValue("stepStatus", DeployStepStatus.COMPENSATION_FAILED.name())
        );
    }

    @Override
    @Transactional
    public void saveTargetSnapshot(
            final long deployTargetKey,
            final DeploySnapshotScope snapshotScope,
            final String stepCode,
            final DeploySnapshotKind snapshotKind,
            final String sourceOfTruth,
            final JsonNode snapshotData
    ) {
        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("deployTargetKey", deployTargetKey)
                .addValue("snapshotScope", snapshotScope.name())
                .addValue("stepCode", support.normalizeOptionalText(stepCode))
                .addValue("snapshotKind", snapshotKind.name())
                .addValue("sourceOfTruth", support.normalizeOptionalText(sourceOfTruth))
                .addValue("snapshotDataJson", support.toJson(snapshotData))
                .addValue("capturedAt", OffsetDateTime.now(ZoneOffset.UTC));

        jdbcTemplate.update("""
                        INSERT INTO tc_deploy_target_snapshot (
                            deploy_target_key,
                            snapshot_scope,
                            step_code,
                            snapshot_kind,
                            source_of_truth,
                            snapshot_data_json,
                            captured_at,
                            created_by
                        ) VALUES (
                            :deployTargetKey,
                            :snapshotScope,
                            :stepCode,
                            :snapshotKind,
                            :sourceOfTruth,
                            CAST(:snapshotDataJson AS JSONB),
                            :capturedAt,
                            'SYSTEM'
                        )
                        """,
                parameters
        );
    }

    @Override
    public Optional<DeployStoredSnapshot> findLatestTargetSnapshot(
            final long deployTargetKey,
            final DeploySnapshotScope snapshotScope,
            final String stepCode,
            final DeploySnapshotKind snapshotKind
    ) {
        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("deployTargetKey", deployTargetKey)
                .addValue("snapshotScope", snapshotScope.name())
                .addValue("stepCode", support.normalizeOptionalText(stepCode))
                .addValue("snapshotKind", snapshotKind.name());

        final List<DeployStoredSnapshot> rows = jdbcTemplate.query("""
                        SELECT deploy_target_snapshot_key,
                               deploy_target_key,
                               snapshot_scope,
                               step_code,
                               snapshot_kind,
                               source_of_truth,
                               snapshot_data_json,
                               captured_at
                          FROM tc_deploy_target_snapshot
                         WHERE deploy_target_key = :deployTargetKey
                           AND snapshot_scope = :snapshotScope
                           AND snapshot_kind = :snapshotKind
                           AND ((:stepCode IS NULL AND step_code IS NULL) OR step_code = :stepCode)
                         ORDER BY deploy_target_snapshot_key DESC
                         LIMIT 1
                        """,
                parameters,
                (resultSet, rowNum) -> new DeployStoredSnapshot(
                        resultSet.getLong("deploy_target_snapshot_key"),
                        resultSet.getLong("deploy_target_key"),
                        DeploySnapshotScope.valueOf(resultSet.getString("snapshot_scope")),
                        resultSet.getString("step_code"),
                        DeploySnapshotKind.valueOf(resultSet.getString("snapshot_kind")),
                        resultSet.getString("source_of_truth"),
                        support.parseJson(resultSet.getString("snapshot_data_json")),
                        resultSet.getObject("captured_at", OffsetDateTime.class)
                )
        );

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.getFirst());
    }

      @Override
    public Optional<DeploySummarySnapshot> findDeploySummary(final long deployKey) {
        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("deployKey", deployKey);

        final StringBuilder sql = new StringBuilder("""
                        SELECT deploy_key,
                               deploy_name,
                               event_type,
                               deploy_status,
                               execution_policy,
                               created_at,
                               created_at as started_at,
                               updated_at as finished_at,
                               last_error_code,
                               last_error_message
                          FROM tc_deploy d
                         WHERE d.deploy_key = :deployKey
                        """);
        support.appendKnownDeployCommandFilters(
                sql,
                parameters,
                "d"
        );
        final List<DeploySummarySnapshot> rows = jdbcTemplate.query(
                sql.toString(),
                parameters,
                (resultSet, rowNum) -> support.mapDeploySummary(resultSet, summarizeTargets(deployKey))
        );

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.getFirst());
    }

    @Override
    public Optional<DeployPlanCommand> findRedeployCommand(final long deployKey) {
        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("deployKey", deployKey);

        final StringBuilder sql = new StringBuilder("""
                        SELECT deploy_name,
                               event_type,
                               execution_policy,
                               desired_common_state_json
                          FROM tc_deploy d
                         WHERE d.deploy_key = :deployKey
                        """);
        support.appendKnownDeployCommandFilters(
                sql,
                parameters,
                "d"
        );
        final List<DeployPlanCommand> commands = jdbcTemplate.query(
                sql.toString(),
                parameters,
                (resultSet, rowNum) -> {
                    final JsonNode commonState = support.parseJson(resultSet.getString("desired_common_state_json"));
                    final String interfaceType = support.normalizeOptionalText(commonState.path("interfaceType").asString(null));
                    final JsonNode uiMessageNode = commonState.get("uiMessage");
                    if (!(uiMessageNode instanceof ObjectNode uiMessage)) {
                        throw new IllegalStateException("desired_common_state_json.uiMessage 는 JSON object 여야 합니다. deployKey="
                                + deployKey);
                    }
                    final DeployPlanCommand.CommonRequest commonRequest =
                            new DeployPlanCommand.CommonRequest(interfaceType, uiMessage.deepCopy());
                    final List<DeployPlanCommand.TargetRequest> targets = loadRedeployTargets(
                            deployKey,
                            parameters,
                            commonRequest.uiMessage()
                    );
                    return new DeployPlanCommand(
                            resultSet.getString("deploy_name"),
                            com.nori.tc.ui.core.model.UiCommandEventType.fromText(resultSet.getString("event_type")),
                            com.nori.tc.ui.core.deploy.DeployExecutionPolicy.fromText(resultSet.getString("execution_policy")),
                            commonRequest,
                            targets
                    );
                }
        );

        if (commands.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(commands.getFirst());
    }

    @Override
    public List<String> findActiveTraceIds(final long deployKey) {
        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("deployKey", deployKey);

        return jdbcTemplate.query("""
                        SELECT DISTINCT s.current_trace_id
                          FROM tc_deploy_target_plan p
                          JOIN tc_deploy_target_state s
                            ON s.deploy_target_key = p.deploy_target_key
                         WHERE p.deploy_key = :deployKey
                           AND s.current_trace_id IS NOT NULL
                           AND s.target_status IN ('PENDING', 'RUNNING', 'ROLLING_BACK')
                         ORDER BY s.current_trace_id ASC
                        """,
                parameters,
                (resultSet, rowNum) -> resultSet.getString("current_trace_id")
        );
    }

    @Override
    @Transactional
    public void markTargetsCanceled(
            final long deployKey,
            final OffsetDateTime finishedAt,
            final String lastErrorCode,
            final String lastErrorMessage
    ) {
        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("deployKey", deployKey)
                .addValue("finishedAt", finishedAt)
                .addValue("lastErrorCode", support.normalizeOptionalText(lastErrorCode))
                .addValue("lastErrorMessage", support.normalizeOptionalText(lastErrorMessage));

        jdbcTemplate.update("""
                        UPDATE tc_deploy_target_state s
                           SET target_status = :targetStatus,
                               current_step_status = :stepStatus,
                               current_trace_id = NULL,
                               current_step_timeout_at = NULL,
                               last_error_code = COALESCE(:lastErrorCode, s.last_error_code),
                               last_error_message = COALESCE(:lastErrorMessage, s.last_error_message),
                               finished_at = COALESCE(s.finished_at, :finishedAt),
                               updated_at = CURRENT_TIMESTAMP,
                               row_version = row_version + 1
                          FROM tc_deploy_target_plan p
                         WHERE p.deploy_key = :deployKey
                           AND p.deploy_target_key = s.deploy_target_key
                           AND s.target_status IN ('PENDING', 'RUNNING', 'ROLLING_BACK')
                        """,
                parameters
                        .addValue("targetStatus", DeployTargetStatus.CANCELED.name())
                        .addValue("stepStatus", DeployStepStatus.CANCELED.name())
        );
    }

    @Override
    @Transactional
    public boolean deleteDeploy(final long deployKey) {
        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("deployKey", deployKey);

        jdbcTemplate.update("""
                        DELETE FROM tc_deploy_target_snapshot
                         WHERE deploy_target_key IN (
                                SELECT deploy_target_key
                                  FROM tc_deploy_target_plan
                                 WHERE deploy_key = :deployKey
                         )
                        """,
                parameters
        );
        jdbcTemplate.update("""
                        DELETE FROM tc_deploy_target_state
                         WHERE deploy_target_key IN (
                                SELECT deploy_target_key
                                  FROM tc_deploy_target_plan
                                 WHERE deploy_key = :deployKey
                         )
                        """,
                parameters
        );
        jdbcTemplate.update("""
                        DELETE FROM tc_deploy_target_plan
                         WHERE deploy_key = :deployKey
                        """,
                parameters
        );
        final int deletedCount = jdbcTemplate.update("""
                        DELETE FROM tc_deploy
                         WHERE deploy_key = :deployKey
                        """,
                parameters
        );
        return deletedCount > 0;
    }

    @Override
    public PagedResponse<DeployHistoryView> findDeployHistory(
            final String modelName,
            final String requestedBy,
            final OffsetDateTime fromCreatedAt,
            final OffsetDateTime toCreatedAtExclusive,
            final boolean terminalOnly,
            final UiPageRequest pageRequest
    ) {
        final StringBuilder countSql = new StringBuilder("""
                SELECT COUNT(*)
                  FROM tc_deploy d
                 WHERE 1 = 1
                """);
                final StringBuilder dataSql = new StringBuilder("""
                SELECT d.deploy_key,
                       d.deploy_name,
                       d.event_type,
                       d.deploy_status,
                       d.execution_policy,
                       d.created_at,
                       d.created_at AS started_at,
                       d.updated_at AS finished_at,
                       d.created_by,
                       d.desired_common_state_json ->> 'interfaceType' AS interface_type,
                       d.desired_common_state_json #>> '{uiMessage,modelInfo,modelName}' AS selected_model_name,
                       d.desired_common_state_json #>> '{uiMessage,modelInfo,modelVersion}' AS selected_model_version,
                       d.desired_common_state_json #>> '{uiMessage,businessJar,jarFileName}' AS selected_business_jar_name,
                       COALESCE(target_counts.total_count, 0) AS total_count,
                       COALESCE(target_counts.pending_count, 0) AS pending_count,
                       COALESCE(target_counts.no_change_count, 0) AS no_change_count,
                       COALESCE(target_counts.running_count, 0) AS running_count,
                       COALESCE(target_counts.completed_count, 0) AS completed_count,
                       COALESCE(target_counts.failed_count, 0) AS failed_count,
                       COALESCE(target_counts.rolling_back_count, 0) AS rolling_back_count,
                       COALESCE(target_counts.rolled_back_count, 0) AS rolled_back_count,
                       COALESCE(target_counts.compensation_failed_count, 0) AS compensation_failed_count,
                       COALESCE(target_counts.canceled_count, 0) AS canceled_count
                  FROM tc_deploy d
                  LEFT JOIN LATERAL (
                        SELECT COUNT(*)::INT AS total_count,
                               COUNT(*) FILTER (WHERE s.target_status = 'PENDING')::INT AS pending_count,
                               COUNT(*) FILTER (WHERE s.target_status = 'NO_CHANGE')::INT AS no_change_count,
                               COUNT(*) FILTER (WHERE s.target_status = 'RUNNING')::INT AS running_count,
                               COUNT(*) FILTER (WHERE s.target_status = 'COMPLETED')::INT AS completed_count,
                               COUNT(*) FILTER (WHERE s.target_status = 'FAILED')::INT AS failed_count,
                               COUNT(*) FILTER (WHERE s.target_status = 'ROLLING_BACK')::INT AS rolling_back_count,
                               COUNT(*) FILTER (WHERE s.target_status = 'ROLLED_BACK')::INT AS rolled_back_count,
                               COUNT(*) FILTER (WHERE s.target_status = 'COMPENSATION_FAILED')::INT AS compensation_failed_count,
                               COUNT(*) FILTER (WHERE s.target_status = 'CANCELED')::INT AS canceled_count
                          FROM tc_deploy_target_plan p
                          JOIN tc_deploy_target_state s
                            ON s.deploy_target_key = p.deploy_target_key
                         WHERE p.deploy_key = d.deploy_key
                  ) target_counts ON TRUE
                 WHERE 1 = 1
                """);
        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("offset", pageRequest.offset())
                .addValue("limit", pageRequest.limit());

        support.appendHistoryFilters(
                countSql,
                parameters,
                modelName,
                requestedBy,
                fromCreatedAt,
                toCreatedAtExclusive,
                terminalOnly
        );
        support.appendHistoryFilters(
                dataSql,
                parameters,
                modelName,
                requestedBy,
                fromCreatedAt,
                toCreatedAtExclusive,
                terminalOnly
        );
        dataSql.append("""

                 ORDER BY d.created_at DESC, d.deploy_key DESC
                 LIMIT :limit
                 OFFSET :offset
                """);

        final Long totalCount = jdbcTemplate.queryForObject(countSql.toString(), parameters, Long.class);
        final List<DeployHistoryView> items = jdbcTemplate.query(
                dataSql.toString(),
                parameters,
                (resultSet, rowNum) -> support.mapDeployHistory(resultSet)
        );

        return PagedResponse.of(
                items,
                pageRequest.offset(),
                pageRequest.limit(),
                totalCount == null ? 0L : totalCount
        );
    }

    @Override
    public DeployTargetCounts summarizeTargets(final long deployKey) {
        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("deployKey", deployKey);

        return jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)::INT AS total_count,
                               COUNT(*) FILTER (WHERE s.target_status = 'PENDING')::INT AS pending_count,
                               COUNT(*) FILTER (WHERE s.target_status = 'NO_CHANGE')::INT AS no_change_count,
                               COUNT(*) FILTER (WHERE s.target_status = 'RUNNING')::INT AS running_count,
                               COUNT(*) FILTER (WHERE s.target_status = 'COMPLETED')::INT AS completed_count,
                               COUNT(*) FILTER (WHERE s.target_status = 'FAILED')::INT AS failed_count,
                               COUNT(*) FILTER (WHERE s.target_status = 'ROLLING_BACK')::INT AS rolling_back_count,
                               COUNT(*) FILTER (WHERE s.target_status = 'ROLLED_BACK')::INT AS rolled_back_count,
                               COUNT(*) FILTER (WHERE s.target_status = 'COMPENSATION_FAILED')::INT AS compensation_failed_count,
                               COUNT(*) FILTER (WHERE s.target_status = 'CANCELED')::INT AS canceled_count
                          FROM tc_deploy_target_plan p
                          JOIN tc_deploy_target_state s
                            ON s.deploy_target_key = p.deploy_target_key
                         WHERE p.deploy_key = :deployKey
                        """,
                parameters,
                (resultSet, rowNum) -> new DeployTargetCounts(
                        resultSet.getInt("total_count"),
                        resultSet.getInt("pending_count"),
                        resultSet.getInt("no_change_count"),
                        resultSet.getInt("running_count"),
                        resultSet.getInt("completed_count"),
                        resultSet.getInt("failed_count"),
                        resultSet.getInt("rolling_back_count"),
                        resultSet.getInt("rolled_back_count"),
                        resultSet.getInt("compensation_failed_count"),
                        resultSet.getInt("canceled_count")
                )
        );
    }

    @Override
    public DeployTargetPage findDeployTargets(
            final long deployKey,
            final int page,
            final int size,
            final DeployTargetStatus statusFilter,
            final String eqpIdFilter
    ) {
        final int offset = page * size;
        final StringBuilder countSql = new StringBuilder("""
                SELECT COUNT(*)
                  FROM tc_deploy_target_plan p
                  JOIN tc_deploy_target_state s
                    ON s.deploy_target_key = p.deploy_target_key
                 WHERE p.deploy_key = :deployKey
                """);
        final StringBuilder dataSql = new StringBuilder("""
                SELECT p.deploy_target_key,
                       p.eqp_id,
                       s.target_status,
                       s.current_step_order,
                       s.current_step_code,
                       s.current_step_status,
                       s.rollback_required,
                       s.last_error_code,
                       s.last_error_message,
                       s.updated_at
                  FROM tc_deploy_target_plan p
                  JOIN tc_deploy_target_state s
                    ON s.deploy_target_key = p.deploy_target_key
                 WHERE p.deploy_key = :deployKey
                """);

        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("deployKey", deployKey)
                .addValue("limit", size)
                .addValue("offset", offset);

        support.appendTargetFilters(countSql, parameters, statusFilter, eqpIdFilter);
        support.appendTargetFilters(dataSql, parameters, statusFilter, eqpIdFilter);
        dataSql.append("""
                
                 ORDER BY p.target_order ASC
                 LIMIT :limit
                 OFFSET :offset
                """);

        final long totalElements = jdbcTemplate.queryForObject(
                countSql.toString(),
                parameters,
                Long.class
        );
        final int totalPages = totalElements == 0L ? 0 : (int) Math.ceil((double) totalElements / size);
        final List<DeployTargetSnapshot> targets = jdbcTemplate.query(
                dataSql.toString(),
                parameters,
                (resultSet, rowNum) -> support.mapDeployTarget(resultSet)
        );

        return new DeployTargetPage(
                deployKey,
                page,
                size,
                totalElements,
                totalPages,
                targets
        );
    }

    private JsonNode buildUiMessageOverride(
            final JsonNode commonUiMessage,
            final String targetEqpParamVersion
    ) {
        final String commonEqpParamVersion = support.normalizeOptionalText(
                commonUiMessage == null ? null : commonUiMessage.path("eqpParams").path("version").asString(null)
        );
        if (targetEqpParamVersion == null || Objects.equals(commonEqpParamVersion, targetEqpParamVersion)) {
            return null;
        }

        final ObjectNode root = support.objectMapper().createObjectNode();
        root.putObject("eqpParams").put("version", targetEqpParamVersion);
        return root;
    }

    private List<DeployPlanCommand.TargetRequest> loadRedeployTargets(
            final long deployKey,
            final MapSqlParameterSource parameters,
            final JsonNode commonUiMessage
    ) {
        return jdbcTemplate.query("""
                        SELECT eqp_id,
                               desired_state_json
                          FROM tc_deploy_target_plan
                         WHERE deploy_key = :deployKey
                         ORDER BY target_order ASC
                        """,
                parameters,
                (resultSet, rowNum) -> {
                    final JsonNode desiredState = support.parseJson(resultSet.getString("desired_state_json"));
                    final String targetEqpParamVersion = support.normalizeOptionalText(
                            desiredState.path("eqpParamVersion").asString(null)
                    );
                    return new DeployPlanCommand.TargetRequest(
                            resultSet.getString("eqp_id"),
                            buildUiMessageOverride(commonUiMessage, targetEqpParamVersion)
                    );
                }
        );
    }

}
