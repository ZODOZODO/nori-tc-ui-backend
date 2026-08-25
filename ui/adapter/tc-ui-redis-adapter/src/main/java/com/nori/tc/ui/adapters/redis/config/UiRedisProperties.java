package com.nori.tc.ui.adapters.redis.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * UI Backend Redis 연결 설정입니다.
 *
 * <p>Redis 연결 정보는 Spring Boot 표준 설정인 {@code spring.data.redis.*}를
 * source of truth로 사용합니다. gateway/business 구분은 key prefix 기준의 논리 역할이며,
 * 물리 Redis 인스턴스는 1개입니다.</p>
 */
@ConfigurationProperties(prefix = "spring.data.redis")
public class UiRedisProperties {

    private static final Logger log = LoggerFactory.getLogger(UiRedisProperties.class);

    private String host = "localhost";
    private int port = 6379;
    private int database = 0;
    private String username;
    private String password;

    /**
     * Redis command timeout(`spring.data.redis.timeout`).
     *
     * <p>이 클래스가 연결 팩토리를 직접 만들기 때문에(Boot 의 Redis auto-configuration 은
     * application.yaml 에서 제외돼 있다) 이 키를 여기서 읽지 않으면 표준 프로퍼티가 조용히 무시되고
     * Lettuce 기본값 60 초가 적용된다. 그러면 §9.3 안전 관계식
     * {@code redisCommandTimeout + selfFence + clockSkewMargin < leaseTtl - heartbeat} 을 만족할 수 없어
     * 조정 원장이 켜진 구성에서 기동이 실패한다(Plan §8 B26 — 관계식은 실측 client 설정으로 강제).</p>
     */
    private Duration timeout = Duration.ofSeconds(1);

    /** Redis connect timeout(`spring.data.redis.connect-timeout`). */
    private Duration connectTimeout = Duration.ofSeconds(1);

    @PostConstruct
    public void validate() {
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("spring.data.redis.host 는 필수 설정입니다.");
        }
        if (port <= 0) {
            throw new IllegalStateException("spring.data.redis.port 는 양수여야 합니다. port=" + port);
        }
        if (database < 0) {
            throw new IllegalStateException("spring.data.redis.database 는 0 이상이어야 합니다. database=" + database);
        }
        requirePositive("timeout", timeout);
        requirePositive("connect-timeout", connectTimeout);
        if (password == null || password.isBlank()) {
            log.warn("spring.data.redis.password 가 설정되지 않았습니다. 운영 환경에서는 반드시 설정이 필요합니다.");
        }
        log.info("UiRedisProperties 검증 완료. redis={}:{}, database={}, timeout={}, connectTimeout={}",
                host, port, database, timeout, connectTimeout);
    }

    private static void requirePositive(final String name, final Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("spring.data.redis." + name + " 은 양수여야 합니다. value=" + value);
        }
    }

    public RedisNodeProperties getConnection() {
        return new RedisNodeProperties(host, port, database, username, password);
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(final Duration timeout) {
        this.timeout = timeout;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(final Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public String getHost() {
        return host;
    }

    public void setHost(final String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(final int port) {
        this.port = port;
    }

    public int getDatabase() {
        return database;
    }

    public void setDatabase(final int database) {
        this.database = database;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(final String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(final String password) {
        this.password = password;
    }

    /**
     * Redis 단일 노드 연결 정보입니다.
     */
    public static class RedisNodeProperties {

        private final String host;
        private final int port;
        private final int database;
        private final String username;
        private final String password;

        public RedisNodeProperties(
                final String host,
                final int port,
                final int database,
                final String username,
                final String password
        ) {
            this.host = host;
            this.port = port;
            this.database = database;
            this.username = username;
            this.password = password;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public int getDatabase() {
            return database;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }
    }
}
