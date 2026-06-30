package com.nori.tc.ui.adapter.db;

import com.nori.tc.ui.core.port.db.PasswordHistoryPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * {@link PasswordHistoryPort} 의 JDBC 구현 어댑터입니다. {@code tc_user_password_history}(Flyway V3) 에
 * 비밀번호 변경 이력을 INSERT 하고 사용자별 최신순으로 SELECT 합니다(재사용 금지 검사용).
 *
 * <p>기존 {@code JdbcAuditLogPort} 와 동일하게 {@link NamedParameterJdbcTemplate} 를 사용합니다.</p>
 */
@Repository
public class JdbcPasswordHistoryPort implements PasswordHistoryPort {

    private static final String INSERT_SQL = """
            INSERT INTO tc_user_password_history (user_pk, password_hash, changed_at)
            VALUES (:userPk, :passwordHash, :changedAt)
            """;

    private static final String RECENT_SQL = """
            SELECT password_hash
              FROM tc_user_password_history
             WHERE user_pk = :userPk
             ORDER BY changed_at DESC, history_pk DESC
             LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcPasswordHistoryPort(final NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate is null");
    }

    @Override
    public List<String> recentHashes(final long userPk, final int limit) {
        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userPk", userPk)
                .addValue("limit", limit);
        return jdbcTemplate.queryForList(RECENT_SQL, params, String.class);
    }

    @Override
    public void record(final long userPk, final String passwordHash, final OffsetDateTime changedAt) {
        Objects.requireNonNull(passwordHash, "passwordHash is null");
        Objects.requireNonNull(changedAt, "changedAt is null");
        final MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userPk", userPk)
                .addValue("passwordHash", passwordHash)
                .addValue("changedAt", Timestamp.from(changedAt.toInstant()));
        jdbcTemplate.update(INSERT_SQL, params);
    }
}
