package com.nori.tc.ui.adapter.db;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.nori.tc.ui.core.deploy.DeployExecutionPolicy;
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
import java.util.Objects;

/**
 * Deploy JDBC 저장소의 SQL/JSON/RowMapper 보조 책임을 분리합니다.
 *
 * <p>어댑터 본체는 유스케이스 흐름만 유지하고, SQL 세부 구현은 이 지원 클래스로 위임합니다.</p>
 */
final class DeployJdbcStoreSupport {

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

    DeploySummarySnapshot mapDeploySummary(
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
