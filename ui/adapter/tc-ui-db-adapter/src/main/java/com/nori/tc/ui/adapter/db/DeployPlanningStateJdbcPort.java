package com.nori.tc.ui.adapter.db;

import com.nori.tc.ui.core.deploy.DeployCurrentState;
import com.nori.tc.ui.core.port.db.DeployPlanningStatePort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * deploy planner current state 조회용 JDBC 어댑터입니다.
 *
 * <p>20260319 jar relation 변경 이후 tc_eqp.gateway_jar_key/business_jar_key를 직접 읽어야 하므로
 * legacy TcJar* store를 경유하지 않고 planner 전용 조회를 분리합니다.</p>
 */
@Repository
public class DeployPlanningStateJdbcPort implements DeployPlanningStatePort {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public DeployPlanningStateJdbcPort(final NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate is null");
    }

    @Override
    public Optional<DeployCurrentState> findCurrentStateByEqpId(final String eqpId) {
        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("eqpId", eqpId);

        final List<DeployCurrentState> rows = jdbcTemplate.query("""
                        SELECT e.eqp_key,
                               e.eqp_id,
                               e.comm_interface,
                               e.comm_mode,
                               e.eqp_ip,
                               e.eqp_port,
                               e.model_version_key,
                               e.applied_param_version,
                               e.gateway_jar_key,
                               e.business_jar_key,
                               s.socket_protocol_type,
                               h.device_id AS hsms_device_id
                          FROM tc_eqp e
                          LEFT JOIN tc_eqp_socket s
                            ON s.eqp_key = e.eqp_key
                          LEFT JOIN tc_eqp_hsms h
                            ON h.eqp_key = e.eqp_key
                         WHERE e.eqp_id = :eqpId
                        """,
                parameters,
                (resultSet, rowNum) -> mapCurrentState(resultSet)
        );

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.getFirst());
    }

    private DeployCurrentState mapCurrentState(final ResultSet resultSet) throws SQLException {
        return new DeployCurrentState(
                resultSet.getLong("eqp_key"),
                resultSet.getString("eqp_id"),
                resultSet.getString("comm_interface"),
                resultSet.getString("comm_mode"),
                resultSet.getString("eqp_ip"),
                resultSet.getInt("eqp_port"),
                resultSet.getLong("model_version_key"),
                resultSet.getString("applied_param_version"),
                nullableLong(resultSet, "gateway_jar_key"),
                nullableLong(resultSet, "business_jar_key"),
                resultSet.getString("socket_protocol_type"),
                nullableInt(resultSet, "hsms_device_id")
        );
    }

    private Long nullableLong(final ResultSet resultSet, final String columnLabel) throws SQLException {
        final long value = resultSet.getLong(columnLabel);
        return resultSet.wasNull() ? null : value;
    }

    private Integer nullableInt(final ResultSet resultSet, final String columnLabel) throws SQLException {
        final int value = resultSet.getInt(columnLabel);
        return resultSet.wasNull() ? null : value;
    }
}
