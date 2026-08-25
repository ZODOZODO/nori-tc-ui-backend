package com.nori.tc.ui.adapters.redis.config;

import com.nori.tc.ui.adapters.redis.async.AsyncResultStoreService;
import com.nori.tc.ui.adapters.redis.comm.CommLogRedisQueryAdapter;
import com.nori.tc.ui.adapters.redis.coordination.DeployExecutionRedisAdapter;
import com.nori.tc.ui.adapters.redis.dlq.BusinessDlqRedisService;
import com.nori.tc.ui.adapters.redis.dlq.GatewayDlqRedisService;
import com.nori.tc.ui.adapters.redis.lease.GatewayShardLeaseRedisAdapter;
import com.nori.tc.ui.adapters.redis.registry.DeployTracePubSubListener;
import com.nori.tc.ui.adapters.redis.registry.DeployTraceRedisAdapter;
import com.nori.tc.ui.adapters.redis.registry.DualResponsePubSubListener;
import com.nori.tc.ui.adapters.redis.registry.DualResponseRedisAdapter;
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
        GatewayShardLeaseRedisAdapter.class,
        DualResponsePubSubListener.class,
        DualResponseRedisAdapter.class,
        RedisEqpRepairTaskStore.class,
        UiSessionCacheService.class
})
public class UiRedisAdapterModuleConfiguration {
}
