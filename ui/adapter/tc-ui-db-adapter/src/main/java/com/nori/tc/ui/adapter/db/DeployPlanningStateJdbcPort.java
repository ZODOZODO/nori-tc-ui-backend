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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    // 단건/배치 조회가 동일한 SELECT 절을 공유해 light 필드 동등성을 보장한다(WHERE 만 상이).
    private static final String CURRENT_STATE_SELECT = """
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
                   e.route_partition,
                   s.socket_protocol_type,
                   h.device_id AS hsms_device_id
              FROM tc_eqp e
              LEFT JOIN tc_eqp_socket s
                ON s.eqp_key = e.eqp_key
              LEFT JOIN tc_eqp_hsms h
                ON h.eqp_key = e.eqp_key
            """;

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

        final List<DeployCurrentState> rows = jdbcTemplate.query(
                CURRENT_STATE_SELECT + " WHERE e.eqp_id = :eqpId",
                parameters,
                (resultSet, rowNum) -> mapCurrentStateRow(resultSet, resolveEquipmentProfile(resultSet.getString("eqp_id")))
        );

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.getFirst());
    }

    /**
     * preview 전용 경량 배치 — 단건 SQL 을 IN-절로 확장하고 equipmentProfile 을 로드하지 않습니다(R2).
     * SELECT 절은 단건과 동일하므로 light 필드는 단건 조회와 동등합니다.
     */
    @Override
    public Map<String, DeployCurrentState> findCurrentLightStatesByEqpIds(final List<String> eqpIds) {
        if (eqpIds.isEmpty()) {
            return Map.of();
        }

        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("eqpIds", eqpIds);

        final List<DeployCurrentState> rows = jdbcTemplate.query(
                CURRENT_STATE_SELECT + " WHERE e.eqp_id IN (:eqpIds)",
                parameters,
                (resultSet, rowNum) -> mapCurrentStateRow(resultSet, null)
        );

        final Map<String, DeployCurrentState> result = new LinkedHashMap<>();
        for (final DeployCurrentState row : rows) {
            result.put(row.eqpId(), row);
        }
        return result;
    }

    /**
     * plan 전용 전체 배치 — light IN 조회 + {@link EqpManagementDbSupport#loadSnapshotsByEqpIds(List)} 배치 +
     * 단건과 동일한 {@link #toEquipmentProfile} 를 per-eqp in-memory 적용합니다(R3). 쿼리 수는 대상 N 과 무관.
     */
    @Override
    public Map<String, DeployCurrentState> findCurrentStatesByEqpIds(final List<String> eqpIds) {
        if (eqpIds.isEmpty()) {
            return Map.of();
        }

        final Map<String, EqpManagementSnapshot> snapshots = eqpManagementDbSupport.loadSnapshotsByEqpIds(eqpIds);

        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("eqpIds", eqpIds);

        final List<DeployCurrentState> rows = jdbcTemplate.query(
                CURRENT_STATE_SELECT + " WHERE e.eqp_id IN (:eqpIds)",
                parameters,
                (resultSet, rowNum) -> {
                    final String eqpId = resultSet.getString("eqp_id");
                    final UiEquipmentProfileSnapshot equipmentProfile = Optional.ofNullable(snapshots.get(eqpId))
                            .map(this::toEquipmentProfile)
                            .orElse(null);
                    return mapCurrentStateRow(resultSet, equipmentProfile);
                }
        );

        final Map<String, DeployCurrentState> result = new LinkedHashMap<>();
        for (final DeployCurrentState row : rows) {
            result.put(row.eqpId(), row);
        }
        return result;
    }

    private UiEquipmentProfileSnapshot resolveEquipmentProfile(final String eqpId) {
        return eqpManagementDbSupport.loadSnapshotByEqpId(eqpId)
                .map(this::toEquipmentProfile)
                .orElse(null);
    }

    private DeployCurrentState mapCurrentStateRow(
            final ResultSet resultSet,
            final UiEquipmentProfileSnapshot equipmentProfile
    ) throws SQLException {
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
                nullableInt(resultSet, "hsms_device_id"),
                nullableInt(resultSet, "route_partition"),
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
                // 8번째 슬롯 modelKey 의 의미는 modelVersionKey(모델 버전 ID)로 통일한다.
                // 소비 측 gateway 가 info.modelKey() 를 그대로 modelVersionKey 로 쓴다
                // (PlcEquipmentSpecReloader / PlcSpecBootstrap "의미 변경 금지" / PlcPollGroupRegistry).
                // 나머지 2벌(DeployServiceSnapshotSupport / DeployServiceCommandSupport)도 이미 버전 ID 다.
                snapshot.eqp().modelVersionKey(),
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
                snapshot.eqp().updatedAt() == null ? null : snapshot.eqp().updatedAt().toString(),
                // desired_state 는 null(never-started)을 보존한다 — null→ENDED 무음 승격 금지(R-A5).
                snapshot.runtimeState() == null ? null : snapshot.runtimeState().desiredState(),
                snapshot.plc() == null ? null : new UiEquipmentProfileSnapshot.PlcSettingsSnapshot(
                        snapshot.plc().plcProtocol(),
                        snapshot.plc().transport(),
                        snapshot.plc().unitId(),
                        snapshot.plc().pollTimeoutMs(),
                        snapshot.plc().reconnectMaxRetries(),
                        snapshot.plc().enabledPollGroups(),
                        // current-state 재구성은 tc_eqp_plc 만 읽는다(tc_model_plc_* 아님). 디코드 스펙은 발행 직전 재조회(§2.3).
                        List.of()
                )
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
