package com.nori.tc.ui.adapter.db;

import com.nori.tc.ui.core.deploy.DeployCurrentState;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.model.UiEquipmentProfileSnapshot;
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
    private final EqpManagementDbSupport eqpManagementDbSupport;

    public DeployPlanningStateJdbcPort(
            final NamedParameterJdbcTemplate jdbcTemplate,
            final EqpManagementDbSupport eqpManagementDbSupport
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate is null");
        this.eqpManagementDbSupport = Objects.requireNonNull(eqpManagementDbSupport, "eqpManagementDbSupport is null");
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
        final String eqpId = resultSet.getString("eqp_id");
        final UiEquipmentProfileSnapshot equipmentProfile = eqpManagementDbSupport.loadSnapshotByEqpId(eqpId)
                .map(this::toEquipmentProfile)
                .orElse(null);
        return new DeployCurrentState(
                resultSet.getLong("eqp_key"),
                eqpId,
                resultSet.getString("comm_interface"),
                resultSet.getString("comm_mode"),
                resultSet.getString("eqp_ip"),
                resultSet.getInt("eqp_port"),
                resultSet.getLong("model_version_key"),
                resultSet.getString("applied_param_version"),
                nullableLong(resultSet, "gateway_jar_key"),
                nullableLong(resultSet, "business_jar_key"),
                resultSet.getString("socket_protocol_type"),
                nullableInt(resultSet, "hsms_device_id"),
                equipmentProfile
        );
    }

    private UiEquipmentProfileSnapshot toEquipmentProfile(final EqpManagementSnapshot snapshot) {
        return new UiEquipmentProfileSnapshot(
                snapshot.eqp().eqpKey(),
                snapshot.eqp().eqpId(),
                snapshot.eqp().commInterface() == null ? null : snapshot.eqp().commInterface().name(),
                snapshot.socket() == null ? null : snapshot.socket().socketProtocolType(),
                snapshot.hsms() == null ? null : snapshot.hsms().deviceId(),
                snapshot.eqp().eqpIp(),
                snapshot.eqp().eqpPort(),
                snapshot.model() == null ? null : snapshot.model().modelKey(),
                snapshot.eqp().gatewayJarKey(),
                snapshot.eqp().businessJarKey(),
                snapshot.eqp().commMode(),
                snapshot.eqp().routePartition(),
                snapshot.eqp().enabled(),
                snapshot.hsms() == null ? null : new UiEquipmentProfileSnapshot.HsmsSettingsSnapshot(
                        snapshot.hsms().deviceId(),
                        snapshot.eqp().commMode(),
                        snapshot.hsms().t3Timeout(),
                        snapshot.hsms().t5Timeout(),
                        snapshot.hsms().t6Timeout(),
                        snapshot.hsms().t7Timeout(),
                        snapshot.hsms().t8Timeout(),
                        snapshot.hsms().linkTestEnabled(),
                        snapshot.hsms().linkTestInterval(),
                        snapshot.hsms().maxMsgBytes()
                ),
                snapshot.socket() == null ? null : new UiEquipmentProfileSnapshot.SocketSettingsSnapshot(
                        snapshot.socket().socketProtocolType(),
                        snapshot.eqp().commMode(),
                        snapshot.socket().charset(),
                        snapshot.socket().heartbeatEnabled(),
                        snapshot.socket().heartbeatInterval(),
                        snapshot.socket().readTimeout(),
                        snapshot.socket().writeTimeout(),
                        snapshot.socket().maxFrameSizeBytes(),
                        snapshot.socket().keepAliveEnabled()
                ),
                snapshot.runtimeState() == null ? null : new UiEquipmentProfileSnapshot.CurrentStateSnapshot(
                        snapshot.runtimeState().controlState() == null ? null : snapshot.runtimeState().controlState().name(),
                        snapshot.runtimeState().eqpState() == null ? null : snapshot.runtimeState().eqpState().name(),
                        snapshot.runtimeState().sinceAt() == null ? null : snapshot.runtimeState().sinceAt().toString(),
                        snapshot.runtimeState().reasonCode(),
                        snapshot.runtimeState().reasonDetail(),
                        snapshot.runtimeState().updatedAt() == null ? null : snapshot.runtimeState().updatedAt().toString()
                ),
                snapshot.portStatuses().stream()
                        .map(port -> new UiEquipmentProfileSnapshot.PortStatusSnapshot(
                                port.portId(),
                                port.portType() == null ? null : port.portType().name(),
                                port.portState(),
                                port.carrierId(),
                                port.carrierType() == null ? null : port.carrierType().name(),
                                port.carrierState() == null ? null : port.carrierState().name(),
                                port.updatedAt() == null ? null : port.updatedAt().toString()
                        ))
                        .toList(),
                snapshot.logPolicy() == null ? null : new UiEquipmentProfileSnapshot.LogPolicySnapshot(
                        snapshot.logPolicy().logLevel() == null ? null : snapshot.logPolicy().logLevel().name(),
                        snapshot.logPolicy().logRetentionDays(),
                        snapshot.logPolicy().logPath(),
                        snapshot.logPolicy().updatedAt() == null ? null : snapshot.logPolicy().updatedAt().toString()
                ),
                snapshot.params().stream()
                        .map(param -> new UiEquipmentProfileSnapshot.ParamSnapshot(
                                param.eqpParamKey(),
                                param.paramName(),
                                param.paramVersion(),
                                param.paramValue(),
                                param.updatedAt() == null ? null : param.updatedAt().toString()
                        ))
                        .toList(),
                snapshot.eqp().updatedAt() == null ? null : snapshot.eqp().updatedAt().toString()
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
