package com.nori.tc.ui.adapters.redis.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * UI Backend Redis 연결 설정 바인딩 클래스입니다.
 *
 * <p>Gateway Redis(6379)와 Business Redis(6380) 두 인스턴스에 대한
 * 연결 정보를 독립적으로 바인딩합니다.
 * Spring Boot 기본 {@code spring.data.redis.*} 설정은 사용하지 않습니다.</p>
 *
 * <p>바인딩 대상 (config/tc-redis.properties):</p>
 * <pre>
 * tc.ui.backend.redis.gateway.host=192.168.0.13
 * tc.ui.backend.redis.gateway.port=6379
 * tc.ui.backend.redis.gateway.password=...
 *
 * tc.ui.backend.redis.business.host=192.168.0.13
 * tc.ui.backend.redis.business.port=6380
 * tc.ui.backend.redis.business.password=...
 * </pre>
 */
@ConfigurationProperties(prefix = "tc.ui.backend.redis")
public class UiRedisProperties {

    private static final Logger log = LoggerFactory.getLogger(UiRedisProperties.class);

    /** Gateway Redis(6379) 연결 설정 - DLQ/Quarantine 조회·삭제 용도 */
    private RedisNodeProperties gateway;

    /** Business Redis(6380) 연결 설정 - DLQ 조회·삭제 + 토큰 캐시 + 비동기 결과 저장 용도 */
    private RedisNodeProperties business;

    /**
     * 애플리케이션 기동 시 필수 설정 값의 존재 여부와 유효성을 검증합니다.
     *
     * <p>gateway, business 각각 host/port 가 누락되면 기동을 중단합니다.
     * 보안상 password 누락은 경고 로그만 출력하고 기동은 허용합니다.</p>
     */
    @PostConstruct
    public void validate() {
        validateNode("gateway", gateway);
        validateNode("business", business);
        log.info("UiRedisProperties 검증 완료. gateway={}:{}, business={}:{}",
                gateway.getHost(), gateway.getPort(),
                business.getHost(), business.getPort());
    }

    /**
     * 개별 Redis 노드 설정의 필수 항목을 검증합니다.
     *
     * @param name 노드 이름 (예외 메시지 구분용)
     * @param node 검증할 노드 설정 객체
     */
    private void validateNode(final String name, final RedisNodeProperties node) {
        if (node == null) {
            throw new IllegalStateException(
                    "tc.ui.backend.redis." + name + " 설정이 누락되었습니다.");
        }
        if (node.getHost() == null || node.getHost().isBlank()) {
            throw new IllegalStateException(
                    "tc.ui.backend.redis." + name + ".host 는 필수 설정입니다.");
        }
        if (node.getPort() <= 0) {
            throw new IllegalStateException(
                    "tc.ui.backend.redis." + name + ".port 는 양수여야 합니다. port=" + node.getPort());
        }
        if (node.getPassword() == null || node.getPassword().isBlank()) {
            // 개발 환경은 password 없이 사용 가능하므로 WARN 수준으로만 알림
            log.warn("tc.ui.backend.redis.{}.password 가 설정되지 않았습니다. "
                    + "운영 환경에서는 반드시 설정이 필요합니다.", name);
        }
    }

    public RedisNodeProperties getGateway() {
        return gateway;
    }

    public void setGateway(final RedisNodeProperties gateway) {
        this.gateway = gateway;
    }

    public RedisNodeProperties getBusiness() {
        return business;
    }

    public void setBusiness(final RedisNodeProperties business) {
        this.business = business;
    }

    /**
     * Redis 단일 노드 연결 정보입니다.
     *
     * <p>host, port, password 세 값으로 Lettuce 연결을 구성합니다.
     * database 는 기본값(0)을 사용합니다.</p>
     */
    public static class RedisNodeProperties {

        /** Redis 서버 호스트명 또는 IP 주소 */
        private String host;

        /** Redis 서버 포트 번호 */
        private int port;

        /**
         * Redis 인증 비밀번호.
         * 인증이 필요 없는 환경에서는 공백 또는 null 로 설정합니다.
         */
        private String password;

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

        public String getPassword() {
            return password;
        }

        public void setPassword(final String password) {
            this.password = password;
        }
    }
}
