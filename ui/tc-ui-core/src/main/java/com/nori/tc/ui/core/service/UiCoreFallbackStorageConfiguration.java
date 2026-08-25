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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis/DB 어댑터가 없는 테스트 환경을 위한 fallback 저장소 구성입니다.
 *
 * <p>실제 앱에서는 Redis/DB 어댑터가 우선 주입되고, 이 구성은 누락 시에만 동작합니다.</p>
 *
 * <p><b>조정 원장 대역 4 종은 {@code @ConditionalOnMissingBean} 을 쓰지 않는다.</b> 그 조건은 빈 정의
 * 등록 순서로 판정되는데, 이 구성(core)이 {@code UiCoordinationConfiguration}(starter)보다 먼저
 * 처리되면 조정 빈이 아직 없어 대역이 등록되고, 그 뒤 조정 빈까지 등록되어 주입 지점이 후보 2 개로
 * 깨진다(2026-08-26 P6 통합 검증에서 실제 기동 실패). 그래서 조정 configuration 의 클래스 조건과
 * 정확히 배타인 프로퍼티 조건으로 갈랐다 — 조정이 명시적으로 꺼진 구성에서만 대역이 등록된다.</p>
 */
@Configuration(proxyBeanMethods = false)
class UiCoreFallbackStorageConfiguration {

    /** 조정 configuration 의 클래스 조건과 같은 prefix — 두 조건이 정확히 배타여야 한다. */
    private static final String COORDINATION_PREFIX = "tc.ui.backend.coordination";

    @Bean
    @ConditionalOnMissingBean(EqpRepairTaskPort.class)
    EqpRepairTaskPort inMemoryEqpRepairTaskPort() {
        return new InMemoryEqpRepairTaskStore();
    }

    @Bean
    @ConditionalOnProperty(prefix = COORDINATION_PREFIX, name = "enabled", havingValue = "false")
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
    @ConditionalOnProperty(prefix = COORDINATION_PREFIX, name = "enabled", havingValue = "false")
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
    @ConditionalOnProperty(prefix = COORDINATION_PREFIX, name = "enabled", havingValue = "false")
    UiScenarioCatalogGuard disabledUiScenarioCatalogGuard() {
        return UiScenarioCatalogGuard.disabled();
    }

    /**
     * 조정 원장 구성이 없을 때 쓰는 permission catalog 게이트입니다.
     *
     * @return 항상 동기로 보고하는 게이트
     */
    @Bean
    @ConditionalOnProperty(prefix = COORDINATION_PREFIX, name = "enabled", havingValue = "false")
    UiPermissionCatalogGuard disabledUiPermissionCatalogGuard() {
        return UiPermissionCatalogGuard.disabled();
    }

    /**
     * 조정 원장 구성이 없을 때 쓰는 상태 표면입니다.
     *
     * @return 항상 준비된 것으로 보고하는 상태
     */
    @Bean
    @ConditionalOnProperty(prefix = COORDINATION_PREFIX, name = "enabled", havingValue = "false")
    UiCoordinationStatus disabledUiCoordinationStatus() {
        return UiCoordinationStatus.disabled();
    }
}
