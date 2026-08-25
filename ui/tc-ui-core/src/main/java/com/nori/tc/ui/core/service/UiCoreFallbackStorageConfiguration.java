package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.coordination.DirectUiEqpMutationCoordinator;
import com.nori.tc.ui.core.coordination.UiCoordinationStatus;
import com.nori.tc.ui.core.coordination.UiEqpMutationCoordinator;
import com.nori.tc.ui.core.coordination.UiPermissionCatalogGuard;
import com.nori.tc.ui.core.coordination.UiScenarioCatalogGuard;
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

    /**
     * 조정 원장 구성이 없을 때 쓰는 즉시 적용 전략입니다.
     *
     * <p>starter 의 조정 configuration 이 자기 구현을 등록하면 이 빈은 만들어지지 않습니다.</p>
     *
     * @param directScenarioService 기존 direct scenario 실행 서비스
     * @return 즉시 적용 coordinator
     */
    @Bean
    @ConditionalOnMissingBean(UiEqpMutationCoordinator.class)
    UiEqpMutationCoordinator directUiEqpMutationCoordinator(
            final EqpDirectScenarioService directScenarioService
    ) {
        return new DirectUiEqpMutationCoordinator(directScenarioService);
    }

    /**
     * 조정 원장 구성이 없을 때 쓰는 scenario catalog 게이트입니다.
     *
     * @return 항상 통과시키는 게이트
     */
    @Bean
    @ConditionalOnMissingBean(UiScenarioCatalogGuard.class)
    UiScenarioCatalogGuard disabledUiScenarioCatalogGuard() {
        return UiScenarioCatalogGuard.disabled();
    }

    /**
     * 조정 원장 구성이 없을 때 쓰는 permission catalog 게이트입니다.
     *
     * @return 항상 동기로 보고하는 게이트
     */
    @Bean
    @ConditionalOnMissingBean(UiPermissionCatalogGuard.class)
    UiPermissionCatalogGuard disabledUiPermissionCatalogGuard() {
        return UiPermissionCatalogGuard.disabled();
    }

    /**
     * 조정 원장 구성이 없을 때 쓰는 상태 표면입니다.
     *
     * @return 항상 준비된 것으로 보고하는 상태
     */
    @Bean
    @ConditionalOnMissingBean(UiCoordinationStatus.class)
    UiCoordinationStatus disabledUiCoordinationStatus() {
        return UiCoordinationStatus.disabled();
    }
}
