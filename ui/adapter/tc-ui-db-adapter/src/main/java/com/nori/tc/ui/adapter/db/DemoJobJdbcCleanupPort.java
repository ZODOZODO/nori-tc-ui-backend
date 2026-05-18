package com.nori.tc.ui.adapter.db;

import com.nori.tc.ui.core.port.db.DemoJobCleanupPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * tc_demo_job 테이블 정합성 검증/정리를 JDBC로 수행하는 어댑터입니다.
 *
 * <p>tc_demo_job은 nori-tc-demo-tester 애플리케이션이 관리하지만 동일 PostgreSQL을 공유하므로,
 * UI Backend가 EQP 삭제 시 직접 검증/정리를 수행해 FK 위반을 방지합니다.</p>
 */
@Repository
public class DemoJobJdbcCleanupPort implements DemoJobCleanupPort {

    private static final String SQL_FIND_ACTIVE_JOB_IDS = """
            SELECT demo_job_id
              FROM tc_demo_job
             WHERE eqp_key = :eqpKey
               AND job_state IN (
                   'QUEUED','PREPARING','SYNCING_SPEC','CONNECTING',
                   'AWAITING_TRIGGER','RUNNING','CLEANING_UP','CANCEL_REQUESTED'
               )
             ORDER BY requested_at ASC
            """;

    private static final String SQL_DELETE_ALL_BY_EQP_KEY = """
            DELETE FROM tc_demo_job
             WHERE eqp_key = :eqpKey
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public DemoJobJdbcCleanupPort(final NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate is null");
    }

    @Override
    public List<String> findActiveDemoJobIds(final long eqpKey) {
        final MapSqlParameterSource params = new MapSqlParameterSource("eqpKey", eqpKey);
        return jdbcTemplate.queryForList(SQL_FIND_ACTIVE_JOB_IDS, params, String.class);
    }

    @Override
    @Transactional
    public int deleteAllByEqpKey(final long eqpKey) {
        final MapSqlParameterSource params = new MapSqlParameterSource("eqpKey", eqpKey);
        return jdbcTemplate.update(SQL_DELETE_ALL_BY_EQP_KEY, params);
    }
}
