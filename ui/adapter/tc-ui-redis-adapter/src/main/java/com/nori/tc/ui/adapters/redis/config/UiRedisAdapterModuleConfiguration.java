package com.nori.tc.ui.adapters.redis.config;

import com.nori.tc.ui.adapters.redis.async.AsyncResultStoreService;
import com.nori.tc.ui.adapters.redis.comm.CommLogRedisQueryAdapter;
import com.nori.tc.ui.adapters.redis.coordination.DeployExecutionRedisAdapter;
import com.nori.tc.ui.adapters.redis.dlq.BusinessDlqRedisService;
import com.nori.tc.ui.adapters.redis.dlq.GatewayDlqRedisService;
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
        DeployExecutionRedisAdapter.class,
        DeployTracePubSubListener.class,
        DeployTraceRedisAdapter.class,
        BusinessDlqRedisService.class,
        GatewayDlqRedisService.class,
        DualResponsePubSubListener.class,
        DualResponseRedisAdapter.class,
        RedisEqpRepairTaskStore.class,
        UiSessionCacheService.class
})
public class UiRedisAdapterModuleConfiguration {
}
