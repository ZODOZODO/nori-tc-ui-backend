package com.nori.tc.ui.adapter.db;

import com.nori.tc.ui.adapter.db.support.AbstractContainersTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * W1-5 (AC-W1-5) — 비밀번호 이력 어댑터 영속 검증.
 *
 * <p>실 PostgreSQL(Testcontainers) + inline DDL 로 {@code tc_user_password_history}(Flyway V3 스키마)를 만들고,
 * {@link JdbcPasswordHistoryPort#record} 가 이력 행을 적재하고
 * {@link JdbcPasswordHistoryPort#recentHashes} 가 사용자별 최신순 limit N 으로 조회하는지 검증한다.
 * 운영/라이브 DDL 은 Flyway V3 게이트로 이월하며, 본 테스트는 inline DDL 로만 검증한다.</p>
 */
class JdbcPasswordHistoryPortTest extends AbstractContainersTest {

    /** Flyway V3 의 tc_user_password_history 스키마를 그대로 재현(FK 는 본 테스트 단독 검증이라 생략). */
    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS tc_user_password_history (
              history_pk    BIGSERIAL    PRIMARY KEY,
              user_pk       BIGINT       NOT NULL,
              password_hash VARCHAR(255) NOT NULL,
              changed_at    TIMESTAMPTZ  NOT NULL
            )
            """;

    private JdbcPasswordHistoryPort historyPort;

    @BeforeEach
    void setUp() {
        final DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        final NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        jdbcTemplate.getJdbcTemplate().execute("DROP TABLE IF EXISTS tc_user_password_history");
        jdbcTemplate.getJdbcTemplate().execute(DDL);
        this.historyPort = new JdbcPasswordHistoryPort(jdbcTemplate);
    }

    @Test
    void recordThenQueryReturnsLatestFirstWithinLimit() {
        final long userPk = 42L;
        final OffsetDateTime base = OffsetDateTime.now();
        // 변경 시각 오름차순으로 6개 적재 → 최신은 hash-6
        for (int i = 1; i <= 6; i++) {
            historyPort.record(userPk, "hash-" + i, base.plusSeconds(i));
        }
        // 다른 사용자 이력은 섞여 있어도 격리되어야 한다.
        historyPort.record(99L, "other-user-hash", base.plusSeconds(100));

        final List<String> recent = historyPort.recentHashes(userPk, 5);

        assertEquals(List.of("hash-6", "hash-5", "hash-4", "hash-3", "hash-2"), recent,
                "최신 변경 시각순으로 limit 5개만 반환해야 한다(다른 사용자 이력 제외).");
    }

    @Test
    void recentHashesReturnsEmptyWhenNoHistory() {
        assertEquals(List.of(), historyPort.recentHashes(7L, 5),
                "이력이 없으면 빈 목록을 반환해야 한다.");
    }
}
