package com.nori.tc.ui.adapter.db;

import com.nori.tc.ui.adapter.db.support.AbstractContainersTest;
import com.nori.tc.ui.domain.audit.UiAuditAction;
import com.nori.tc.ui.domain.audit.UiAuditEvent;
import com.nori.tc.ui.domain.audit.UiAuditOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * W1-6 (AC-W1-6) — 감사 로그 어댑터 영속 검증.
 *
 * <p>실 PostgreSQL(Testcontainers) + inline DDL 로 {@code tc_ui_audit_log} 를 만들고,
 * {@link JdbcAuditLogPort#record} 가 actor·작업·대상·결과·시각을 실제 행으로 적재하는지 검증한다.
 * 운영 DDL(create_all_table / 라이브)은 W3 Flyway V2 게이트로 이월하며, 본 테스트는 inline DDL 로만 검증한다.</p>
 */
class JdbcAuditLogPortTest extends AbstractContainersTest {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS tc_ui_audit_log (
              audit_pk    BIGSERIAL    PRIMARY KEY,
              actor       VARCHAR(100) NOT NULL,
              action      VARCHAR(40)  NOT NULL,
              target      VARCHAR(255) NOT NULL,
              outcome     VARCHAR(20)  NOT NULL,
              occurred_at TIMESTAMPTZ  NOT NULL,
              detail      VARCHAR(500)
            )
            """;

    private NamedParameterJdbcTemplate jdbcTemplate;
    private JdbcAuditLogPort auditLogPort;

    @BeforeEach
    void setUp() {
        final DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        this.jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        jdbcTemplate.getJdbcTemplate().execute("DROP TABLE IF EXISTS tc_ui_audit_log");
        jdbcTemplate.getJdbcTemplate().execute(DDL);
        this.auditLogPort = new JdbcAuditLogPort(jdbcTemplate);
    }

    @Test
    void recordPersistsAuditRow() {
        auditLogPort.record(UiAuditEvent.loginSuccess("alice"));

        final Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT actor, action, outcome FROM tc_ui_audit_log",
                new MapSqlParameterSource());

        assertEquals("alice", row.get("actor"));
        assertEquals(UiAuditAction.LOGIN.name(), row.get("action"));
        assertEquals(UiAuditOutcome.SUCCESS.name(), row.get("outcome"));
    }

    @Test
    void recordPersistsTargetAndDetail() {
        auditLogPort.record(UiAuditEvent.loginFailure("bob", "password_mismatch"));

        final Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT actor, action, target, outcome, detail FROM tc_ui_audit_log",
                new MapSqlParameterSource());

        assertEquals("bob", row.get("actor"));
        assertEquals(UiAuditAction.LOGIN.name(), row.get("action"));
        assertEquals("bob", row.get("target"));
        assertEquals(UiAuditOutcome.FAILURE.name(), row.get("outcome"));
        assertEquals("password_mismatch", row.get("detail"));
    }
}
