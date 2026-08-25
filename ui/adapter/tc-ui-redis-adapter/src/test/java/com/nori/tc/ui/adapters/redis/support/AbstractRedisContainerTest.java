package com.nori.tc.ui.adapters.redis.support;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 조정 원장 IT 공통 베이스(Testcontainers).
 *
 * <p>이 모듈의 다른 테스트는 {@code RedisTemplate} 을 mock 으로 세우지만, 조정 원장은 Lua 원자성과
 * TTL 만료가 본질이라 mock 으로는 아무것도 증명하지 못합니다 — mock 은 "우리가 기대하는 대로 동작한다"
 * 만 확인할 뿐 <b>동시 접근에서 무엇이 거부되는지</b>를 재현하지 못합니다.</p>
 *
 * <p>싱글톤 Redis 컨테이너 + {@code reuse=true}. Docker 미가용(CI norilab 러너) 시 하위 테스트
 * 전체 skip. 레포/모듈별 의도적 중복 컨벤션(common/business/gateway 선례).</p>
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractRedisContainerTest {

    private static final int REDIS_PORT = 6379;

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT)
                    .withReuse(true);

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            REDIS.start();
        }
    }

    /**
     * 컨테이너 Redis 에 붙는 연결 팩토리를 만듭니다.
     *
     * @return 시작된 연결 팩토리
     */
    protected static LettuceConnectionFactory newConnectionFactory() {
        final RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(REDIS_PORT));
        final LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }
}
