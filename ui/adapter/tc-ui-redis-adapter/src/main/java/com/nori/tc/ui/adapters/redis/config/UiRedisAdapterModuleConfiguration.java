package com.nori.tc.ui.adapters.redis.config;

import com.nori.tc.ui.adapters.redis.async.AsyncResultStoreService;
import com.nori.tc.ui.adapters.redis.comm.CommLogRedisQueryAdapter;
import com.nori.tc.ui.adapters.redis.coordination.DeployExecutionRedisAdapter;
import com.nori.tc.ui.adapters.redis.dlq.BusinessDlqRedisService;
import com.nori.tc.ui.adapters.redis.dlq.GatewayDlqRedisService;
import com.nori.tc.ui.adapters.redis.registry.DeployTracePubSubListener;
import com.nori.tc.ui.adapters.redis.registry.DeployTraceRedisAdapter;
import com.nori.tc.ui.adapters.redis.repair.RedisEqpRepairTaskStore;
import com.nori.tc.ui.adapters.redis.session.UiSessionCacheService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * UI Redis adapter bean graph를 명시적으로 고정합니다.
 */
@Configuration(proxyBeanMethods = false)
@Import({
        UiRedisConfiguration.class,
        AsyncResultStoreService.class,
        CommLogRedisQueryAdapter.class,
        // DeployExecutionRedisAdapter 는 여기서 등록하지 않는다 — 스테레오타입이 아니고 협력자
        // (공통 FencedLeaseRedisStore)를 조정 configuration 만 갖고 있다. 여기서 함께 등록하면
        // DeployExecutionCoordinationPort 후보가 2 개가 되어 앱이 기동하지 못한다.
        DeployTracePubSubListener.class,
        DeployTraceRedisAdapter.class,
        BusinessDlqRedisService.class,
        GatewayDlqRedisService.class,
        // GatewayShardLeaseRedisAdapter 는 여기서 직접 등록하지 않는다 — 조정이 켜진 Gateway 는
        // lease 를 fence epoch hash 로 기록하므로 레거시 raw String 키 조회는 항상 미점유를 낸다.
        // 조정 on/off 를 배타 조건으로 가르는 두 configuration 이 각자 자기 구현을 등록한다.
        UiLegacyGatewayShardLeaseConfiguration.class,
        RedisEqpRepairTaskStore.class,
        UiSessionCacheService.class
})
public class UiRedisAdapterModuleConfiguration {
}
