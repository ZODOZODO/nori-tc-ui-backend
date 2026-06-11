package com.nori.tc.ui.adapter.db;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.nori.tc.ui.core.deploy.DeployActiveEqpConflict;
import com.nori.tc.ui.core.deploy.DeployExecutionPolicy;
import com.nori.tc.ui.core.deploy.DeployHistoryView;
import com.nori.tc.ui.core.deploy.DeployPlanCommand;
import com.nori.tc.ui.core.deploy.DeployPlanningResult;
import com.nori.tc.ui.core.deploy.DeployStatus;
import com.nori.tc.ui.core.deploy.DeployStepStatus;
import com.nori.tc.ui.core.deploy.DeploySummarySnapshot;
import com.nori.tc.ui.core.deploy.DeployTargetCounts;
import com.nori.tc.ui.core.deploy.DeployTargetSnapshot;
import com.nori.tc.ui.core.deploy.DeployTargetStatus;
import com.nori.tc.ui.core.model.UiCommandEventType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Deploy JDBC 저장소의 SQL/JSON/RowMapper 보조 책임을 분리합니다.
 *
 * <p>어댑터 본체는 유스케이스 흐름만 유지하고, SQL 세부 구현은 이 지원 클래스로 위임합니다.</p>
 */
final class DeployJdbcStoreSupport {

    private static final List<String> ALLOWED_EVENT_TYPES = List.of(
            "EQP_CREATE",
            "EQP_UPDATE",
            "EQP_MODEL_UPDATE",
            "EQP_DELETE",
            "EQP_START",
            "EQP_END",
            "EQP_SEND_MESSAGE",
            "EQP_RECONNECT",
            "EQP_PARAMETER_UPDATE",
            "EQP_GATEWAY_JARFILE_UPDATE",
            "EQP_BUSINESS_JARFILE_UPDATE",
            "EQP_REGISTER_DEPLOY"
    );
    private static final List<String> ALLOWED_EXECUTION_POLICIES = List.of(
            "BEST_EFFORT",
            "FAIL_FAST"
    );

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    DeployJdbcStoreSupport(
            final NamedParameterJdbcTemplate jdbcTemplate,
            final ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
    }

    long insertDeploy(
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
        if (command.commonRequest().resolvedScenarioCode() != null) {
            desiredCommonState.put("resolvedScenarioCode", command.commonRequest().resolvedScenarioCode());
        }

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

    long insertTargetPlan(
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

    void insertTargetState(
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

    List<DeployActiveEqpConflict> findActiveEqpConflicts(
            final Collection<String> eqpIds,
            final Long excludedDeployKey
    ) {
        final List<String> normalizedEqpIds = eqpIds == null
                ? List.of()
                : eqpIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        if (normalizedEqpIds.isEmpty()) {
            return List.of();
        }

        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("eqpIds", normalizedEqpIds);
        final StringBuilder sql = new StringBuilder("""
                        SELECT DISTINCT ON (p.eqp_id)
                               p.eqp_id,
                               d.deploy_key,
                               d.deploy_status,
                               d.deploy_name,
                               d.created_by AS requested_by
                          FROM tc_deploy_target_plan p
                          JOIN tc_deploy d
                            ON d.deploy_key = p.deploy_key
                         WHERE p.eqp_id IN (:eqpIds)
                           AND d.deploy_status IN ('READY', 'RUNNING')
                        """);
        if (excludedDeployKey != null) {
            sql.append("""
                           AND d.deploy_key <> :excludedDeployKey
                        """);
            parameters.addValue("excludedDeployKey", excludedDeployKey);
        }
        sql.append("""
                         ORDER BY p.eqp_id ASC,
                                  CASE d.deploy_status
                                      WHEN 'RUNNING' THEN 0
                                      ELSE 1
                                  END,
                                  d.deploy_key ASC
                        """);

        return jdbcTemplate.query(sql.toString(),
                parameters,
                (resultSet, rowNum) -> new DeployActiveEqpConflict(
                        resultSet.getString("eqp_id"),
                        resultSet.getLong("deploy_key"),
                        DeployStatus.valueOf(resultSet.getString("deploy_status")),
                        resultSet.getString("deploy_name"),
                        resultSet.getString("requested_by")
                )
        );
    }

    void appendTargetFilters(
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

    void appendHistoryFilters(
            final StringBuilder sql,
            final MapSqlParameterSource parameters,
            final String modelName,
            final String requestedBy,
            final OffsetDateTime fromCreatedAt,
            final OffsetDateTime toCreatedAtExclusive,
            final boolean terminalOnly
    ) {
        if (modelName != null && !modelName.isBlank()) {
            sql.append("""

                       AND (
                               d.deploy_name ILIKE :modelNamePattern
                            OR d.desired_common_state_json #>> '{uiMessage,modelInfo,modelName}' ILIKE :modelNamePattern
                       )
                    """);
            parameters.addValue("modelNamePattern", "%" + modelName.trim() + "%");
        }
        if (requestedBy != null && !requestedBy.isBlank()) {
            sql.append("\n   AND d.created_by ILIKE :requestedByPattern");
            parameters.addValue("requestedByPattern", "%" + requestedBy.trim() + "%");
        }
        if (fromCreatedAt != null) {
            sql.append("\n   AND d.created_at >= :fromCreatedAt");
            parameters.addValue("fromCreatedAt", fromCreatedAt);
        }
        if (toCreatedAtExclusive != null) {
            sql.append("\n   AND d.created_at < :toCreatedAtExclusive");
            parameters.addValue("toCreatedAtExclusive", toCreatedAtExclusive);
        }
        sql.append("""

                   AND UPPER(TRIM(d.event_type)) IN (:allowedEventTypes)
                   AND UPPER(TRIM(COALESCE(d.execution_policy, ''))) IN (:allowedExecutionPolicies)
                """);
        parameters.addValue("allowedEventTypes", ALLOWED_EVENT_TYPES);
        parameters.addValue("allowedExecutionPolicies", ALLOWED_EXECUTION_POLICIES);
        if (terminalOnly) {
            sql.append("""

                       AND d.deploy_status IN ('COMPLETED', 'PARTIAL_FAILED', 'FAILED', 'CANCELED')
                    """);
            return;
        }

        sql.append("""

                   AND d.deploy_status IN ('PLANNING', 'READY', 'RUNNING')
                """);
    }

    void appendKnownDeployCommandFilters(
            final StringBuilder sql,
            final MapSqlParameterSource parameters,
            final String tableAlias
    ) {
        final String prefix = tableAlias == null || tableAlias.isBlank() ? "" : tableAlias + ".";
        sql.append("\n                   AND UPPER(TRIM(")
                .append(prefix)
                .append("event_type)) IN (:allowedEventTypes)\n")
                .append("                   AND UPPER(TRIM(COALESCE(")
                .append(prefix)
                .append("execution_policy, ''))) IN (:allowedExecutionPolicies)\n");
        parameters.addValue("allowedEventTypes", ALLOWED_EVENT_TYPES);
        parameters.addValue("allowedExecutionPolicies", ALLOWED_EXECUTION_POLICIES);
    }

    DeploySummarySnapshot mapDeploySummary(
            final ResultSet resultSet,
            final DeployTargetCounts targetCounts
    ) throws SQLException {
        final JsonNode commonState = parseJson(resultSet.getString("desired_common_state_json"));
        return new DeploySummarySnapshot(
                resultSet.getLong("deploy_key"),
                resultSet.getString("deploy_name"),
                UiCommandEventType.fromText(resultSet.getString("event_type")),
                normalizeOptionalText(commonState.path("resolvedScenarioCode").asString(null)),
                DeployStatus.valueOf(resultSet.getString("deploy_status")),
                resultSet.getBoolean("pause_requested"),
                DeployExecutionPolicy.fromText(resultSet.getString("execution_policy")),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("started_at", OffsetDateTime.class),
                resultSet.getObject("finished_at", OffsetDateTime.class),
                targetCounts,
                resultSet.getString("last_error_code"),
                resultSet.getString("last_error_message")
        );
    }

    DeployHistoryView mapDeployHistory(final ResultSet resultSet) throws SQLException {
        final DeployStatus deployStatus = DeployStatus.valueOf(resultSet.getString("deploy_status"));
        return new DeployHistoryView(
                resultSet.getLong("deploy_key"),
                resultSet.getString("deploy_name"),
                UiCommandEventType.fromText(resultSet.getString("event_type")),
                deployStatus,
                resultSet.getBoolean("pause_requested"),
                DeployExecutionPolicy.fromText(resultSet.getString("execution_policy")),
                normalizeActor(resultSet.getString("created_by")),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("started_at", OffsetDateTime.class),
                resultSet.getObject("finished_at", OffsetDateTime.class),
                isTerminal(deployStatus),
                normalizeOptionalText(resultSet.getString("interface_type")),
                normalizeOptionalText(resultSet.getString("selected_model_name")),
                normalizeOptionalText(resultSet.getString("selected_model_version")),
                normalizeOptionalText(resultSet.getString("selected_business_jar_name")),
                new DeployTargetCounts(
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

    DeployTargetSnapshot mapDeployTarget(final ResultSet resultSet) throws SQLException {
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

    String toJson(final JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value == null ? objectMapper.nullNode() : value);
        } catch (Exception exception) {
            throw new IllegalStateException("JSON 직렬화에 실패했습니다.", exception);
        }
    }

    JsonNode parseJson(final String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException("JSON 역직렬화에 실패했습니다.", exception);
        }
    }

    ObjectMapper objectMapper() {
        return objectMapper;
    }

    String normalizeActor(final String actor) {
        if (actor == null || actor.isBlank()) {
            return "SYSTEM";
        }
        return actor.trim();
    }

    String normalizeOptionalText(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
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

    private boolean isTerminal(final DeployStatus deployStatus) {
        return switch (deployStatus) {
            case COMPLETED, PARTIAL_FAILED, FAILED, CANCELED -> true;
            case PLANNING, READY, RUNNING -> false;
        };
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
}
