package com.nori.tc.ui.adapter.db.support;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 19주차 W1 — ui-backend DB abnormal 매트릭스 공통 베이스(Testcontainers).
 *
 * <p>싱글톤 PostgreSQL 컨테이너 + {@code reuse=true}. Docker 미가용 시 하위 테스트 전체 skip.
 * 레포/모듈별 의도적 중복(Plan §1.D). 기존 통합테스트는 H2 였으나 DB slow query(pg_sleep)는
 * PostgreSQL 고유 기능이라 실 PG 를 사용한다.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractContainersTest {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine")).withReuse(true);

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
        }
    }
}
