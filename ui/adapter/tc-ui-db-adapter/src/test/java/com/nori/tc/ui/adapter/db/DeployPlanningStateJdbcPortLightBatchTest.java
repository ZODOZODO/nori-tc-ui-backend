package com.nori.tc.ui.adapter.db;

import com.nori.tc.ui.adapter.db.support.AbstractContainersTest;
import com.nori.tc.ui.core.deploy.DeployCurrentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 2 (deploy-bulk-register-perf) — preview 경량 배치 어댑터 동등성 검증(실 PostgreSQL).
 *
 * <p>Phase 0 서비스 테스트는 포트를 lambda stub 하므로 어댑터 SQL 을 태우지 못한다.
 * 이 테스트는 단건 SELECT ↔ 배치 IN SELECT 가 light 필드에서 동일한 {@link DeployCurrentState} 를
 * 만드는지 실 DB 로 증명한다(빈 입력 / 부분 매칭 / 누락 eqpId 포함).</p>
 *
 * <p>equipmentProfile 로더는 mock(Optional.empty) → 단건도 profile=null 이 되어 배치(profile=null)와
 * record 전체가 동일해진다 → light SELECT 컬럼 동등성을 record equals 로 검증.</p>
 */
class DeployPlanningStateJdbcPortLightBatchTest extends AbstractContainersTest {

    private NamedParameterJdbcTemplate jdbcTemplate;
    private DeployPlanningStateJdbcPort port;

    @BeforeEach
    void setUp() {
        final DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);

        jdbcTemplate.getJdbcOperations().execute("DROP TABLE IF EXISTS tc_eqp_hsms");
        jdbcTemplate.getJdbcOperations().execute("DROP TABLE IF EXISTS tc_eqp_socket");
        jdbcTemplate.getJdbcOperations().execute("DROP TABLE IF EXISTS tc_eqp");
        jdbcTemplate.getJdbcOperations().execute("""
                CREATE TABLE tc_eqp (
                    eqp_key               BIGINT PRIMARY KEY,
                    eqp_id                VARCHAR(64) NOT NULL,
                    comm_interface        VARCHAR(16),
                    comm_mode             VARCHAR(16),
                    eqp_ip                VARCHAR(64),
                    eqp_port              INT,
                    model_version_key     BIGINT,
                    applied_param_version VARCHAR(64),
                    gateway_jar_key       BIGINT,
                    business_jar_key      BIGINT,
                    route_partition       INT
                )
                """);
        jdbcTemplate.getJdbcOperations().execute("""
                CREATE TABLE tc_eqp_socket (
                    eqp_key              BIGINT PRIMARY KEY,
                    socket_protocol_type VARCHAR(32)
                )
                """);
        jdbcTemplate.getJdbcOperations().execute("""
                CREATE TABLE tc_eqp_hsms (
                    eqp_key   BIGINT PRIMARY KEY,
                    device_id INT
                )
                """);

        // E1: SOCKET(socket row 有, hsms 無) / E2: HSMS(hsms row 有, socket 無, route_partition NULL)
        jdbcTemplate.getJdbcOperations().execute("""
                INSERT INTO tc_eqp VALUES
                    (1, 'E1', 'SOCKET', 'ACTIVE', '10.0.0.1', 6000, 1001, 'PARAM_V1', 3001, 4001, 0),
                    (2, 'E2', 'HSMS',   'PASSIVE','10.0.0.2', 7000, 1002, NULL,        NULL, NULL,  NULL)
                """);
        jdbcTemplate.getJdbcOperations().execute("INSERT INTO tc_eqp_socket VALUES (1, 'ASCII')");
        jdbcTemplate.getJdbcOperations().execute("INSERT INTO tc_eqp_hsms VALUES (2, 7)");

        final EqpManagementDbSupport eqpManagementDbSupport = mock(EqpManagementDbSupport.class);
        // 경량 배치는 loadSnapshotByEqpId 를 호출하지 않는다. 단건 경로는 호출하므로 empty 로 stub → profile=null 동등.
        when(eqpManagementDbSupport.loadSnapshotByEqpId(any())).thenReturn(Optional.empty());
        port = new DeployPlanningStateJdbcPort(jdbcTemplate, eqpManagementDbSupport);
    }

    @Test
    void emptyInputReturnsEmptyMapWithoutQuery() {
        assertTrue(port.findCurrentLightStatesByEqpIds(List.of()).isEmpty());
    }

    @Test
    void batchReturnsOnlyMatchedEqpIdsAndSkipsMissing() {
        final Map<String, DeployCurrentState> result =
                port.findCurrentLightStatesByEqpIds(List.of("E1", "E2", "MISSING"));

        assertEquals(2, result.size());
        assertTrue(result.containsKey("E1"));
        assertTrue(result.containsKey("E2"));
        assertFalse(result.containsKey("MISSING"));
    }

    @Test
    void batchLightStateEqualsSingleQueryPerEqp() {
        final Map<String, DeployCurrentState> batch =
                port.findCurrentLightStatesByEqpIds(List.of("E1", "E2"));

        // 단건 SELECT(equipmentProfile mock=empty → null) 와 배치 결과가 record 전체 동일해야 한다.
        for (final String eqpId : List.of("E1", "E2")) {
            final DeployCurrentState single = port.findCurrentStateByEqpId(eqpId).orElseThrow();
            assertEquals(single, batch.get(eqpId), "eqpId=" + eqpId + " 단건↔배치 light 필드 불일치");
        }
    }
}
