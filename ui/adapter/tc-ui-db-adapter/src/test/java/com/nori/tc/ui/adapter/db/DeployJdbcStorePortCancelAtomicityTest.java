package com.nori.tc.ui.adapter.db;

import com.nori.tc.ui.core.deploy.DeployStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * S08 D-4 — deploy 취소의 두 쓰기(target 전환 / 헤더 기록)가 한 트랜잭션인지 실 SQL 로 검증합니다.
 *
 * <p>core 쪽 호출 형태는 {@code DeployServiceTest#cancelDeployLeavesNoPartialStateWhenHeaderWriteFails}
 * 가 고정하고, 여기서는 "헤더 기록이 실패하면 target 전환이 되돌려진다" 를 실 DB rollback 으로 확인한다.
 * 실패는 헤더 컬럼의 CHECK 제약으로 인가한다.</p>
 */
class DeployJdbcStorePortCancelAtomicityTest {

    private static final OffsetDateTime CANCELED_AT = OffsetDateTime.parse("2026-08-04T00:00:00Z");

    private NamedParameterJdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private DeployJdbcStorePort port;

    @BeforeEach
    void setUp() {
        final DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:deploy-cancel-atomicity;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "sa",
                ""
        );
        jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        port = new DeployJdbcStorePort(jdbcTemplate, new tools.jackson.databind.json.JsonMapper());

        jdbcTemplate.getJdbcOperations().execute("DROP TABLE IF EXISTS tc_deploy_target_state");
        jdbcTemplate.getJdbcOperations().execute("DROP TABLE IF EXISTS tc_deploy_target_plan");
        jdbcTemplate.getJdbcOperations().execute("DROP TABLE IF EXISTS tc_deploy");
        jdbcTemplate.getJdbcOperations().execute("""
                CREATE TABLE tc_deploy (
                    deploy_key               BIGINT PRIMARY KEY,
                    deploy_status            VARCHAR(32) NOT NULL,
                    target_total_count       INT,
                    target_no_change_count   INT,
                    target_completed_count   INT,
                    target_failed_count      INT,
                    target_rolled_back_count INT,
                    pause_requested          BOOLEAN,
                    last_error_code          VARCHAR(64),
                    last_error_message       VARCHAR(512),
                    updated_at               TIMESTAMP
                )
                """);
        jdbcTemplate.getJdbcOperations().execute("""
                CREATE TABLE tc_deploy_target_plan (
                    deploy_target_key BIGINT PRIMARY KEY,
                    deploy_key        BIGINT NOT NULL
                )
                """);
        jdbcTemplate.getJdbcOperations().execute("""
                CREATE TABLE tc_deploy_target_state (
                    deploy_target_key       BIGINT PRIMARY KEY,
                    target_status           VARCHAR(32) NOT NULL,
                    current_step_status     VARCHAR(32),
                    current_trace_id        VARCHAR(64),
                    current_step_timeout_at TIMESTAMP,
                    last_error_code         VARCHAR(64),
                    last_error_message      VARCHAR(512),
                    finished_at             TIMESTAMP,
                    updated_at              TIMESTAMP,
                    row_version             BIGINT NOT NULL DEFAULT 0
                )
                """);
    }

    @Test
    @DisplayName("S08 D-4: 취소는 target 전환과 헤더 기록을 함께 반영합니다")
    void markDeployCanceledAppliesBothWrites() {
        insertDeploy(1L, DeployStatus.RUNNING);
        insertTarget(11L, 1L, "RUNNING");

        transactionTemplate.executeWithoutResult(status ->
                port.markDeployCanceled(1L, CANCELED_AT, "DEPLOY_CANCELED", "사용자 취소"));

        assertEquals("CANCELED", readDeployStatus(1L));
        assertEquals("CANCELED", readTargetStatus(11L));
    }

    @Test
    @DisplayName("S08 D-4: 헤더 기록이 실패하면 target 전환도 되돌려집니다(부분 반영 0)")
    void markDeployCanceledRollsBackTargetsWhenHeaderWriteFails() {
        insertDeploy(2L, DeployStatus.RUNNING);
        insertTarget(21L, 2L, "RUNNING");
        // 헤더 UPDATE 만 실패시킨다 — target UPDATE 는 성공한 뒤 rollback 되어야 한다.
        jdbcTemplate.getJdbcOperations().execute(
                "ALTER TABLE tc_deploy ADD CONSTRAINT ck_reject_cancel CHECK (deploy_status <> 'CANCELED')");

        assertThrows(RuntimeException.class, () -> transactionTemplate.executeWithoutResult(status ->
                port.markDeployCanceled(2L, CANCELED_AT, "DEPLOY_CANCELED", "사용자 취소")));

        assertEquals("RUNNING", readDeployStatus(2L));
        assertEquals("RUNNING", readTargetStatus(21L));
    }

    private void insertDeploy(final long deployKey, final DeployStatus status) {
        jdbcTemplate.update(
                "INSERT INTO tc_deploy (deploy_key, deploy_status, pause_requested) "
                        + "VALUES (:deployKey, :deployStatus, FALSE)",
                Map.of("deployKey", deployKey, "deployStatus", status.name())
        );
    }

    private void insertTarget(final long targetKey, final long deployKey, final String targetStatus) {
        jdbcTemplate.update(
                "INSERT INTO tc_deploy_target_plan (deploy_target_key, deploy_key) VALUES (:key, :deployKey)",
                Map.of("key", targetKey, "deployKey", deployKey)
        );
        jdbcTemplate.update(
                "INSERT INTO tc_deploy_target_state (deploy_target_key, target_status, updated_at) "
                        + "VALUES (:key, :status, :updatedAt)",
                Map.of("key", targetKey, "status", targetStatus,
                        "updatedAt", OffsetDateTime.now(ZoneOffset.UTC))
        );
    }

    private String readDeployStatus(final long deployKey) {
        return jdbcTemplate.queryForObject(
                "SELECT deploy_status FROM tc_deploy WHERE deploy_key = :deployKey",
                Map.of("deployKey", deployKey),
                String.class
        );
    }

    private String readTargetStatus(final long targetKey) {
        return jdbcTemplate.queryForObject(
                "SELECT target_status FROM tc_deploy_target_state WHERE deploy_target_key = :key",
                Map.of("key", targetKey),
                String.class
        );
    }
}
