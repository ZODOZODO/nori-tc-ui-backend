package com.nori.tc.ui.starter;

import com.nori.tc.ui.core.auth.UiAuthMetrics;
import com.nori.tc.ui.core.auth.UiTokenRecheckWindowCache;
import com.nori.tc.ui.core.registry.DualResponseRegistry;
import com.nori.tc.ui.core.service.DeployRegisterQueryService;
import com.nori.tc.ui.core.service.UiCoreServiceModuleConfiguration;
import com.nori.tc.ui.core.usecase.LoginUseCase;
import com.nori.tc.ui.core.usecase.LogoutUseCase;
import com.nori.tc.ui.core.usecase.ValidateTokenUseCase;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * UI core bean graph를 명시적으로 고정합니다.
 *
 * <p>core 계층 등록은 더 이상 package scan에 의존하지 않고,
 * starter가 import하는 명시적 그래프로만 조립합니다.</p>
 */
@Configuration(proxyBeanMethods = false)
@Import({
        UiAuthMetrics.class,
        UiTokenRecheckWindowCache.class,
        DualResponseRegistry.class,
        DeployRegisterQueryService.class,
        UiCoreServiceModuleConfiguration.class,
        LoginUseCase.class,
        LogoutUseCase.class,
        ValidateTokenUseCase.class
})
public class UiCoreModuleConfiguration {
}
