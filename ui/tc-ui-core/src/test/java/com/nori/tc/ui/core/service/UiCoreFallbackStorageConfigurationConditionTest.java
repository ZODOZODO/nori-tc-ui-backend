package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.coordination.UiCoordinationStatus;
import com.nori.tc.ui.core.coordination.UiEqpMutationCoordinator;
import com.nori.tc.ui.core.coordination.UiPermissionCatalogGuard;
import com.nori.tc.ui.core.coordination.UiScenarioCatalogGuard;
import com.nori.tc.ui.core.port.redis.DeployExecutionCoordinationPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조정 원장 대역 빈이 조정 configuration 과 배타 조건으로 갈라져 있는지 고정합니다.
 *
 * <p>{@code @ConditionalOnMissingBean} 은 빈 정의 등록 순서로 판정되므로, core 가 starter 보다 먼저
 * 처리되면 대역과 조정 빈이 <b>둘 다</b> 등록되어 주입 지점이 후보 2 개로 깨집니다. 이 실패는 앱
 * 기동에서만 드러나고 단위 테스트는 협력자를 직접 넘기므로 보지 못합니다 —
 * 2026-08-26 P6 통합 검증에서 {@code DeployScenarioCatalogService} 가 실제로 이렇게 기동에 실패했습니다.</p>
 */
class UiCoreFallbackStorageConfigurationConditionTest {

    private static final Set<Class<?>> COORDINATION_FALLBACK_TYPES = Set.of(
            UiEqpMutationCoordinator.class,
            UiScenarioCatalogGuard.class,
            UiPermissionCatalogGuard.class,
            UiCoordinationStatus.class,
            DeployExecutionCoordinationPort.class);

    @Test
    @DisplayName("조정 대역 빈은 조정이 명시적으로 꺼진 구성에서만 등록된다")
    void coordinationFallbacksAreRegisteredOnlyWhenCoordinationIsExplicitlyDisabled() {
        final List<Method> beanMethods = List.of(UiCoreFallbackStorageConfiguration.class.getDeclaredMethods());
        assertThat(beanMethods).isNotEmpty();

        int checked = 0;
        for (final Method method : beanMethods) {
            if (!method.isAnnotationPresent(Bean.class)
                    || !COORDINATION_FALLBACK_TYPES.contains(method.getReturnType())) {
                continue;
            }
            checked++;

            assertThat(method.getAnnotation(ConditionalOnMissingBean.class))
                    .as("%s: @ConditionalOnMissingBean 은 등록 순서에 의존해 조정 빈과 동시 등록될 수 있다",
                            method.getName())
                    .isNull();

            final ConditionalOnProperty condition = method.getAnnotation(ConditionalOnProperty.class);
            assertThat(condition)
                    .as("%s: 조정 configuration 과 배타인 프로퍼티 조건이 필요하다", method.getName())
                    .isNotNull();
            assertThat(condition.prefix()).isEqualTo("tc.ui.backend.coordination");
            assertThat(condition.name()).containsExactly("enabled");
            assertThat(condition.havingValue()).isEqualTo("false");
            assertThat(condition.matchIfMissing())
                    .as("%s: matchIfMissing=true 면 조정 기본 활성(프로퍼티 부재) 구성에서 함께 등록된다",
                            method.getName())
                    .isFalse();
        }

        assertThat(checked)
                .as("조정 대역 빈 4 종을 모두 검사해야 한다")
                .isEqualTo(COORDINATION_FALLBACK_TYPES.size());
    }
}
