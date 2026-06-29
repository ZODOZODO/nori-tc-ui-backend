package com.nori.tc.ui.adapter.db;

import com.nori.tc.ui.core.port.db.AuditLogPort;
import com.nori.tc.ui.domain.audit.UiAuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Objects;

/**
 * {@link AuditLogPort} 의 JDBC 구현 어댑터입니다. {@code tc_ui_audit_log} 에 감사 이벤트를 INSERT 합니다.
 *
 * <p><b>best-effort 정책</b>: 감사 기록 실패가 본래 작업(로그인·권한변경)을 중단시키지 않도록
 * {@link DataAccessException} 을 잡아 ERROR 로그(이벤트 컨텍스트 포함)로만 남기고 전파하지 않습니다.
 * 보안 감사 누락은 운영 모니터링 대상이므로 반드시 로그로 가시화합니다(조용한 무시 금지).</p>
 */
@Repository
public class JdbcAuditLogPort implements AuditLogPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcAuditLogPort.class);

    private static final String INSERT_SQL = """
            INSERT INTO tc_ui_audit_log (actor, action, target, outcome, occurred_at, detail)
            VALUES (:actor, :action, :target, :outcome, :occurredAt, :detail)
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcAuditLogPort(final NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate is null");
    }

    @Override
    public void record(final UiAuditEvent event) {
        Objects.requireNonNull(event, "event is null");
        try {
            final MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("actor", event.actor())
                    .addValue("action", event.action().name())
                    .addValue("target", event.target())
                    .addValue("outcome", event.outcome().name())
                    .addValue("occurredAt", Timestamp.from(event.occurredAt().toInstant()))
                    .addValue("detail", event.detail());
            jdbcTemplate.update(INSERT_SQL, params);
        } catch (final DataAccessException ex) {
            // best-effort: 본래 작업을 막지 않되, 감사 누락을 운영 로그에 명시한다.
            log.error("감사 로그 적재 실패(best-effort, 작업은 계속). actor={}, action={}, target={}, outcome={}",
                    event.actor(), event.action(), event.target(), event.outcome(), ex);
        }
    }
}
