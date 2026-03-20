package com.nori.tc.ui.adapter.db;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.nori.tc.ui.core.deploy.DeployExecutionPolicy;
import com.nori.tc.ui.core.deploy.DeployPlanCommand;
import com.nori.tc.ui.core.deploy.DeployPlanningResult;
import com.nori.tc.ui.core.deploy.DeploySnapshotKind;
import com.nori.tc.ui.core.deploy.DeploySnapshotScope;
import com.nori.tc.ui.core.deploy.DeployStatus;
import com.nori.tc.ui.core.deploy.DeployStepStatus;
import com.nori.tc.ui.core.deploy.DeployStoredSnapshot;
import com.nori.tc.ui.core.deploy.DeploySummarySnapshot;
import com.nori.tc.ui.core.deploy.DeployTargetCounts;
import com.nori.tc.ui.core.deploy.DeployTargetPage;
import com.nori.tc.ui.core.deploy.DeployTargetSnapshot;
import com.nori.tc.ui.core.deploy.DeployTargetStatus;
import com.nori.tc.ui.core.deploy.PersistedDeploy;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.port.db.DeployStorePort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
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
    private final ObjectMapper objectMapper;

    public DeployJdbcStorePort(
            final NamedParameterJdbcTemplate jdbcTemplate,
            final ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
    }

    @Override
    @Transactional
    public PersistedDeploy createDeploy(
            final DeployPlanCommand command,
            final DeployPlanningResult planningResult,
            final String actor
    ) {
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        final String normalizedActor = normalizeActor(actor);
        final int totalCount = planningResult.targets().size();
        final int noChangeCount = (int) planningResult.targets().stream()
                .filter(target -> target.noChange())
                .count();

        final long deployKey = insertDeploy(
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
            final long deployTargetKey = insertTargetPlan(
                    deployKey,
                    targetOrder,
                    targetPlan,
                    normalizedActor,
                    now
            );
            insertTargetState(deployTargetKey, targetPlan.noChange(), now);
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
            final OffsetDateTime startedAt,
            final OffsetDateTime finishedAt,
            final String lastErrorCode,
            final String lastErrorMessage
    ) {
        final DeployTargetCounts counts = summarizeTargets(deployKey);
        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("deployKey", deployKey)
                .addValue("deployStatus", deployStatus.name())
                .addValue("startedAt", startedAt)
                .addValue("finishedAt", finishedAt)
                .addValue("targetTotalCount", counts.total())
                .addValue("targetNoChangeCount", counts.noChange())
                .addValue("targetCompletedCount", counts.completed())
                .addValue("targetFailedCount", counts.failed())
                .addValue("targetRolledBackCount", counts.rolledBack())
                .addValue("lastErrorCode", normalizeOptionalText(lastErrorCode))
                .addValue("lastErrorMessage", normalizeOptionalText(lastErrorMessage));

        jdbcTemplate.update("""
                        UPDATE tc_deploy
                           SET deploy_status = :deployStatus,
                               started_at = COALESCE(:startedAt, started_at),
                               finished_at = COALESCE(:finishedAt, finished_at),
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
                .addValue("stepCode", normalizeOptionalText(stepCode))
                .addValue("attemptNo", attemptNo)
                .addValue("traceId", normalizeOptionalText(traceId))
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
                .addValue("stepCode", normalizeOptionalText(stepCode))
                .addValue("attemptNo", attemptNo)
                .addValue("traceId", normalizeOptionalText(traceId))
                .addValue("lastErrorCode", normalizeOptionalText(lastErrorCode))
                .addValue("lastErrorMessage", normalizeOptionalText(lastErrorMessage))
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
                .addValue("lastErrorCode", normalizeOptionalText(lastErrorCode))
                .addValue("lastErrorMessage", normalizeOptionalText(lastErrorMessage))
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
                .addValue("stepCode", normalizeOptionalText(stepCode))
                .addValue("stepStatus", stepStatus == null ? DeployStepStatus.FAILED.name() : stepStatus.name())
                .addValue("lastErrorCode", normalizeOptionalText(lastErrorCode))
                .addValue("lastErrorMessage", normalizeOptionalText(lastErrorMessage))
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
                .addValue("stepCode", normalizeOptionalText(stepCode))
                .addValue("lastErrorCode", normalizeOptionalText(lastErrorCode))
                .addValue("lastErrorMessage", normalizeOptionalText(lastErrorMessage))
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
                .addValue("stepCode", normalizeOptionalText(stepCode))
                .addValue("snapshotKind", snapshotKind.name())
                .addValue("sourceOfTruth", normalizeOptionalText(sourceOfTruth))
                .addValue("snapshotDataJson", toJson(snapshotData))
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
                .addValue("stepCode", normalizeOptionalText(stepCode))
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
                        parseJson(resultSet.getString("snapshot_data_json")),
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

        final List<DeploySummarySnapshot> rows = jdbcTemplate.query("""
                        SELECT deploy_key,
                               deploy_name,
                               event_type,
                               deploy_status,
                               execution_policy,
                               created_at,
                               started_at,
                               finished_at,
                               last_error_code,
                               last_error_message
                          FROM tc_deploy
                         WHERE deploy_key = :deployKey
                        """,
                parameters,
                (resultSet, rowNum) -> mapDeploySummary(resultSet, summarizeTargets(deployKey))
        );

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.getFirst());
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

        appendTargetFilters(countSql, parameters, statusFilter, eqpIdFilter);
        appendTargetFilters(dataSql, parameters, statusFilter, eqpIdFilter);
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
                (resultSet, rowNum) -> mapDeployTarget(resultSet)
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

    private long insertDeploy(
            final DeployPlanCommand command,
            final DeployPlanningResult planningResult,
            final int totalCount,
            final int noChangeCount,
            final String actor,
            final OffsetDateTime now
    ) {
        final ObjectNode desiredCommonState = objectMapper.createObjectNode();
        desiredCommonState.put("interfaceType", command.commonRequest().interfaceType());
        desiredCommonState.set("uiMessage", command.commonRequest().uiMessage().deepCopy());

        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("deployName", command.deployName())
                .addValue("eventType", command.eventType().name())
                .addValue("deployStatus", DeployStatus.PLANNING.name())
                .addValue("executionPolicy", command.executionPolicy().name())
                .addValue("scenarioCatalogVersion", planningResult.scenarioCatalogVersion())
                .addValue("desiredCommonStateJson", toJson(desiredCommonState))
                .addValue("targetTotalCount", totalCount)
                .addValue("targetNoChangeCount", noChangeCount)
                .addValue("createdAt", now)
                .addValue("updatedAt", now)
                .addValue("createdBy", actor)
                .addValue("updatedBy", actor);

        return jdbcTemplate.queryForObject("""
                        INSERT INTO tc_deploy (
                            deploy_name,
                            event_type,
                            deploy_status,
                            execution_policy,
                            scenario_catalog_version,
                            desired_common_state_json,
                            target_total_count,
                            target_no_change_count,
                            target_completed_count,
                            target_failed_count,
                            target_rolled_back_count,
                            created_at,
                            updated_at,
                            created_by,
                            updated_by
                        ) VALUES (
                            :deployName,
                            :eventType,
                            :deployStatus,
                            :executionPolicy,
                            :scenarioCatalogVersion,
                            CAST(:desiredCommonStateJson AS JSONB),
                            :targetTotalCount,
                            :targetNoChangeCount,
                            0,
                            0,
                            0,
                            :createdAt,
                            :updatedAt,
                            :createdBy,
                            :updatedBy
                        )
                        RETURNING deploy_key
                        """,
                parameters,
                Long.class
        );
    }

    private long insertTargetPlan(
            final long deployKey,
            final int targetOrder,
            final com.nori.tc.ui.core.deploy.DeployTargetPlanDraft targetPlan,
            final String actor,
            final OffsetDateTime now
    ) {
        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("deployKey", deployKey)
                .addValue("targetOrder", targetOrder)
                .addValue("eqpKey", targetPlan.currentState() == null ? null : targetPlan.currentState().eqpKey())
                .addValue("eqpId", targetPlan.eqpId())
                .addValue("interfaceType", targetPlan.interfaceType())
                .addValue("resolvedScenarioCode", requireText("resolvedScenarioCode", targetPlan.resolvedScenarioCode()))
                .addValue("resolvedScenarioVersion", requirePositive("resolvedScenarioVersion", targetPlan.resolvedScenarioVersion()))
                .addValue("desiredStateJson", toJson(targetPlan.desiredStateJson()))
                .addValue("deltaJson", toJson(targetPlan.deltaJson()))
                .addValue("resolvedStepPlanJson", toJson(targetPlan.resolvedStepPlanJson()))
                .addValue("createdAt", now)
                .addValue("createdBy", actor);

        return jdbcTemplate.queryForObject("""
                        INSERT INTO tc_deploy_target_plan (
                            deploy_key,
                            target_order,
                            eqp_key,
                            eqp_id,
                            interface_type,
                            resolved_scenario_code,
                            resolved_scenario_version,
                            desired_state_json,
                            delta_json,
                            resolved_step_plan_json,
                            created_at,
                            created_by
                        ) VALUES (
                            :deployKey,
                            :targetOrder,
                            :eqpKey,
                            :eqpId,
                            :interfaceType,
                            :resolvedScenarioCode,
                            :resolvedScenarioVersion,
                            CAST(:desiredStateJson AS JSONB),
                            CAST(:deltaJson AS JSONB),
                            CAST(:resolvedStepPlanJson AS JSONB),
                            :createdAt,
                            :createdBy
                        )
                        RETURNING deploy_target_key
                        """,
                parameters,
                Long.class
        );
    }

    private void insertTargetState(
            final long deployTargetKey,
            final boolean noChange,
            final OffsetDateTime now
    ) {
        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("deployTargetKey", deployTargetKey)
                .addValue("targetStatus", noChange ? DeployTargetStatus.NO_CHANGE.name() : DeployTargetStatus.PENDING.name())
                .addValue("finishedAt", noChange ? now : null)
                .addValue("updatedAt", now);

        jdbcTemplate.update("""
                        INSERT INTO tc_deploy_target_state (
                            deploy_target_key,
                            target_status,
                            current_step_attempt_no,
                            rollback_required,
                            finished_at,
                            updated_at,
                            row_version
                        ) VALUES (
                            :deployTargetKey,
                            :targetStatus,
                            0,
                            FALSE,
                            :finishedAt,
                            :updatedAt,
                            0
                        )
                        """,
                parameters
        );
    }

    private void appendTargetFilters(
            final StringBuilder sql,
            final MapSqlParameterSource parameters,
            final DeployTargetStatus statusFilter,
            final String eqpIdFilter
    ) {
        if (statusFilter != null) {
            sql.append("\n   AND s.target_status = :targetStatus");
            parameters.addValue("targetStatus", statusFilter.name());
        }
        if (eqpIdFilter != null && !eqpIdFilter.isBlank()) {
            sql.append("\n   AND p.eqp_id ILIKE :eqpIdPattern");
            parameters.addValue("eqpIdPattern", "%" + eqpIdFilter.trim() + "%");
        }
    }

    private DeploySummarySnapshot mapDeploySummary(
            final ResultSet resultSet,
            final DeployTargetCounts targetCounts
    ) throws SQLException {
        return new DeploySummarySnapshot(
                resultSet.getLong("deploy_key"),
                resultSet.getString("deploy_name"),
                UiCommandEventType.fromText(resultSet.getString("event_type")),
                DeployStatus.valueOf(resultSet.getString("deploy_status")),
                DeployExecutionPolicy.fromText(resultSet.getString("execution_policy")),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("started_at", OffsetDateTime.class),
                resultSet.getObject("finished_at", OffsetDateTime.class),
                targetCounts,
                resultSet.getString("last_error_code"),
                resultSet.getString("last_error_message")
        );
    }

    private DeployTargetSnapshot mapDeployTarget(final ResultSet resultSet) throws SQLException {
        return new DeployTargetSnapshot(
                resultSet.getLong("deploy_target_key"),
                resultSet.getString("eqp_id"),
                DeployTargetStatus.valueOf(resultSet.getString("target_status")),
                nullableInt(resultSet, "current_step_order"),
                resultSet.getString("current_step_code"),
                nullableStepStatus(resultSet, "current_step_status"),
                resultSet.getBoolean("rollback_required"),
                resultSet.getString("last_error_code"),
                resultSet.getString("last_error_message"),
                resultSet.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private DeployStepStatus nullableStepStatus(
            final ResultSet resultSet,
            final String columnName
    ) throws SQLException {
        final String value = resultSet.getString(columnName);
        if (value == null || value.isBlank()) {
            return null;
        }
        return DeployStepStatus.valueOf(value);
    }

    private Integer nullableInt(final ResultSet resultSet, final String columnName) throws SQLException {
        final int value = resultSet.getInt(columnName);
        return resultSet.wasNull() ? null : value;
    }

    private String toJson(final JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value == null ? objectMapper.nullNode() : value);
        } catch (Exception exception) {
            throw new IllegalStateException("JSON 직렬화에 실패했습니다.", exception);
        }
    }

    private JsonNode parseJson(final String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException("JSON 역직렬화에 실패했습니다.", exception);
        }
    }

    private static String requireText(final String fieldName, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static int requirePositive(final String fieldName, final Integer value) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than 0");
        }
        return value;
    }

    private static String normalizeActor(final String actor) {
        if (actor == null || actor.isBlank()) {
            return "SYSTEM";
        }
        return actor.trim();
    }

    private static String normalizeOptionalText(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
