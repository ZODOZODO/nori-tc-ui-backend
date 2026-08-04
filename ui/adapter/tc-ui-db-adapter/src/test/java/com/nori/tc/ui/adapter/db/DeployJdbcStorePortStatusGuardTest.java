package com.nori.tc.ui.adapter.db;

import com.nori.tc.ui.core.deploy.DeployStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * S08 D-2 — 취소된 deploy 헤더가 뒤늦은 상태 기록으로 부활하지 않는지 실 SQL 로 검증합니다.
 *
 * <p>core 의 CANCELED 체크(읽기)와 상태 기록(쓰기)은 서로 다른 트랜잭션이므로,
 * 경합을 막는 유일한 지점은 UPDATE 자체의 terminal 가드다. 따라서 mock 이 아니라
 * 실 DB(H2)에 SQL 을 태워 "CANCELED 행은 갱신되지 않는다" 를 직접 확인한다.</p>
 */
class DeployJdbcStorePortStatusGuardTest {

    private NamedParameterJdbcTemplate jdbcTemplate;
    private DeployJdbcStorePort port;

    @BeforeEach
    void setUp() {
        final DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:deploy-status-guard;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "sa",
                ""
        );
        jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
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
                    deploy_target_key BIGINT PRIMARY KEY,
                    target_status     VARCHAR(32) NOT NULL
                )
                """);
    }

    @Test
    @DisplayName("S08 D-2: CANCELED deploy 에 뒤늦은 COMPLETED 기록은 반영되지 않습니다")
    void updateDeployStatusDoesNotResurrectCanceledDeploy() {
        insertDeploy(1L, DeployStatus.CANCELED);
        insertTarget(11L, 1L, "CANCELED");

        port.updateDeployStatus(1L, DeployStatus.COMPLETED, false, null, null);

        assertEquals("CANCELED", readDeployStatus(1L));
    }

    @Test
    @DisplayName("S08 D-2: CANCELED 가 아닌 deploy 의 상태 기록은 그대로 반영됩니다(가드가 정상 경로를 막지 않음)")
    void updateDeployStatusStillAdvancesRunningDeploy() {
        insertDeploy(2L, DeployStatus.RUNNING);
        insertTarget(21L, 2L, "COMPLETED");

        port.updateDeployStatus(2L, DeployStatus.COMPLETED, false, null, null);

        assertEquals("COMPLETED", readDeployStatus(2L));
    }

    @Test
    @DisplayName("S08 D-2: RUNNING deploy 에 CANCELED 기록은 반영됩니다(취소 경로 보존)")
    void updateDeployStatusStillAppliesCancel() {
        insertDeploy(3L, DeployStatus.RUNNING);
        insertTarget(31L, 3L, "CANCELED");

        port.updateDeployStatus(3L, DeployStatus.CANCELED, false, "DEPLOY_CANCELED", "사용자 취소");

        assertEquals("CANCELED", readDeployStatus(3L));
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
                "INSERT INTO tc_deploy_target_state (deploy_target_key, target_status) VALUES (:key, :status)",
                Map.of("key", targetKey, "status", targetStatus)
        );
    }

    private String readDeployStatus(final long deployKey) {
        return jdbcTemplate.queryForObject(
                "SELECT deploy_status FROM tc_deploy WHERE deploy_key = :deployKey",
                Map.of("deployKey", deployKey),
                String.class
        );
    }
}
