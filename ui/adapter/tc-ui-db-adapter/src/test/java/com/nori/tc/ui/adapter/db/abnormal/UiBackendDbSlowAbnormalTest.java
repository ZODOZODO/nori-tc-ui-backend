package com.nori.tc.ui.adapter.db.abnormal;

import com.nori.tc.ui.adapter.db.support.AbstractContainersTest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 19주차 W1 — abnormal 매트릭스: "DB slow query"(도표1 경계=DB, 유형=슬로).
 *
 * <p>실 PostgreSQL(Testcontainers)에 {@code pg_sleep} 으로 의도적 slow query 를 인가하고,
 * JDBC query timeout 이 이를 {@link SQLException}(canceling statement)으로 잘라내는지 검증한다.
 * (mock 아님 — 실 PG + 실 slow query. 무한 대기가 아니라 timeout 으로 처리됨을 보장.)</p>
 */
class UiBackendDbSlowAbnormalTest extends AbstractContainersTest {

    /**
     * DB slow 1건 — query timeout(1s) 이 slow query(5s)를 SQLException 으로 차단함을 assert.
     */
    @Test
    void slowQuery_exceedsQueryTimeout() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {

            statement.setQueryTimeout(1);

            assertThrows(
                    SQLException.class,
                    () -> statement.executeQuery("SELECT pg_sleep(5)"),
                    "query timeout(1s)이 slow query(5s)를 SQLException 으로 차단해야 합니다.");
        }
    }
}
