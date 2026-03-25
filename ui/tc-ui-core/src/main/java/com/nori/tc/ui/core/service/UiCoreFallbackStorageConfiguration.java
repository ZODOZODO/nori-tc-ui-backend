package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.port.db.EqpRepairTaskPort;
import com.nori.tc.ui.core.port.redis.DeployExecutionCoordinationPort;
import com.nori.tc.ui.core.port.redis.DeployTraceRedisPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis/DB 어댑터가 없는 테스트 환경을 위한 fallback 저장소 구성입니다.
 *
 * <p>실제 앱에서는 Redis/DB 어댑터가 우선 주입되고, 이 구성은 누락 시에만 동작합니다.</p>
 */
@Configuration(proxyBeanMethods = false)
class UiCoreFallbackStorageConfiguration {

    @Bean
    @ConditionalOnMissingBean(EqpRepairTaskPort.class)
    EqpRepairTaskPort inMemoryEqpRepairTaskPort() {
        return new InMemoryEqpRepairTaskStore();
    }

    @Bean
    @ConditionalOnMissingBean(DeployExecutionCoordinationPort.class)
    DeployExecutionCoordinationPort inMemoryDeployExecutionCoordinationPort() {
        return new InMemoryDeployExecutionCoordinationPort();
    }

    @Bean
    @ConditionalOnMissingBean(DeployTraceRedisPort.class)
    DeployTraceRedisPort inMemoryDeployTraceRedisPort() {
        return new InMemoryDeployTraceRedisPort();
    }
}
