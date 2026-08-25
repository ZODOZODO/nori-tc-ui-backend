package com.nori.tc.ui.adapters.redis.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P5 게이트 — {@code spring.data.redis.timeout} 이 실제 Lettuce client 에 반영되는지 고정한다
 * (SPEC §19.1 "애플리케이션과 Redis 사이 connect/read timeout 명시" / Plan §8 B26).
 *
 * <p>이 레포는 application.yaml 에서 Boot 의 Redis auto-configuration 을 제외하고
 * {@link UiRedisConfiguration} 이 연결 팩토리를 직접 만든다. 그래서 표준 프로퍼티를 여기서 읽지
 * 않으면 exec 배포 config 가 값을 넣어도 조용히 무시되고 Lettuce 기본값 60 초가 적용된다.
 * 그 값으로는 §9.3 안전 관계식
 * {@code redisCommandTimeout + selfFence + clockSkewMargin < leaseTtl - heartbeat}
 * (1s + 2s + 1s &lt; 15s - 5s) 을 만족할 수 없어, 조정 원장이 켜진 구성에서
 * {@code UiCoordinationConfiguration} 의 실측 검증이 기동을 실패시킨다.</p>
 */
class UiRedisConnectionTimeoutTest {

    private static UiRedisProperties properties(final Duration timeout, final Duration connectTimeout) {
        final UiRedisProperties props = new UiRedisProperties();
        props.setHost("127.0.0.1");
        props.setPort(6379);
        props.setTimeout(timeout);
        props.setConnectTimeout(connectTimeout);
        return props;
    }

    @Test
    @DisplayName("설정한 command timeout 이 연결 팩토리에 반영된다")
    void configuredCommandTimeoutIsApplied() {
        final LettuceConnectionFactory factory = new UiRedisConfiguration()
                .uiBackendLettuceConnectionFactory(properties(Duration.ofSeconds(1), Duration.ofSeconds(1)));

        assertEquals(1_000L, factory.getTimeout(),
                "실측 command timeout 이 §9.3 관계식(< 7000ms)을 만족해야 조정 원장 구성이 기동한다");
    }

    @Test
    @DisplayName("기본값도 §9.3 안전 관계식을 만족한다 — 배포 config 가 값을 빠뜨려도 60 초로 떨어지지 않는다")
    void defaultTimeoutSatisfiesLeaseSafetyRelation() {
        final LettuceConnectionFactory factory = new UiRedisConfiguration()
                .uiBackendLettuceConnectionFactory(new UiRedisProperties());

        // redisCommandTimeout + selfFenceMs(2000) + clockSkewMarginMs(1000) < heartbeatTtl(15000) - interval(5000)
        assertEquals(true, factory.getTimeout() + 2_000L + 1_000L < 10_000L,
                "기본값이 관계식을 깨면 값을 명시하지 않은 환경이 전부 기동 실패한다. actualMs=" + factory.getTimeout());
    }
}
