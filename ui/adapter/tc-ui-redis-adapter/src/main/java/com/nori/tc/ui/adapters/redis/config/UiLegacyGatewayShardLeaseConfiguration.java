package com.nori.tc.ui.adapters.redis.config;

import com.nori.tc.common.shard.SharedShardGroupProperties;
import com.nori.tc.ui.adapters.redis.lease.GatewayShardLeaseRedisAdapter;
import com.nori.tc.ui.core.port.redis.GatewayShardLeasePort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 조정 원장을 쓰지 않는 구성의 Gateway shard lease 조회 빈입니다.
 *
 * <p>조정이 켜진 Gateway 는 lease 를 fence epoch hash 로 기록하므로 레거시 raw String 키에는 아무도
 * 쓰지 않습니다 — 그 경로로 조회하면 모든 group 이 미점유로 보입니다. 그래서 두 구현을
 * {@code UiCoordinationConfiguration} 의 클래스 조건과 <b>정확히 배타</b>인 프로퍼티 조건으로 갈랐습니다
 * ({@code UiCoreFallbackStorageConfiguration} 이 조정 대역 4 종을 가른 것과 같은 이유 — 등록 순서에
 * 의존하는 {@code @ConditionalOnMissingBean} 은 후보 2 개로 기동을 깨뜨립니다).</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UiGatewayLeaseProperties.class)
@ConditionalOnProperty(prefix = "tc.ui.backend.coordination", name = "enabled", havingValue = "false")
public class UiLegacyGatewayShardLeaseConfiguration {

    /**
     * raw String lease 키를 읽는 레거시 어댑터를 등록합니다.
     *
     * @param businessStringRedisTemplate Gateway 가 raw String 으로 기록한 lease 를 읽는 template
     * @param shardGroupProperties Gateway 와 공유하는 shard-group 정의
     * @param leaseProperties 레거시 lease 키 prefix 설정
     * @return 레거시 shard lease 조회 포트
     */
    @Bean
    public GatewayShardLeasePort legacyGatewayShardLeasePort(
            @Qualifier("businessStringRedisTemplate") final StringRedisTemplate businessStringRedisTemplate,
            final SharedShardGroupProperties shardGroupProperties,
            final UiGatewayLeaseProperties leaseProperties
    ) {
        return new GatewayShardLeaseRedisAdapter(
                businessStringRedisTemplate, shardGroupProperties, leaseProperties);
    }
}
