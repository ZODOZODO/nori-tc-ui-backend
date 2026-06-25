package com.nori.tc.ui.adapters.web.config;

import com.nori.tc.ui.adapters.web.simulator.DemoRunConnectionReaper;
import com.nori.tc.ui.adapters.web.simulator.DemoRunConnectionRegistry;
import com.nori.tc.ui.adapters.web.simulator.LiveMapSimulatorRunFacade;
import com.nori.tc.ui.core.service.DemoRunConnectionGate;
import com.nori.tc.ui.core.service.EqpManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * AC13 — 신규 레지스트리/reaper 빈이 {@link UiSimulatorConfiguration} 에 @Bean 으로 등록되어 컨텍스트가 정상
 * 로드되는지 검증한다(ComponentScan 미사용 컨벤션 — @Bean 누락 시 facade 가 registry 를 못 받아 기동 실패).
 */
class UiSimulatorConfigurationContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(EqpManagementService.class, () -> mock(EqpManagementService.class))
            .withBean(DemoRunConnectionGate.class, () -> mock(DemoRunConnectionGate.class))
            .withBean(UiDualRequestProperties.class)
            // 연결-후-MES 배리어 비동기화: facade 가 활성화 executor 를 @Qualifier 로 주입받으므로 컨텍스트에 제공한다
            // (운영은 starter 의 demoRunActivationExecutor 빈). 인라인 executor 로 충분 — 본 테스트는 빈 등록만 검증.
            .withBean(LiveMapSimulatorRunFacade.ACTIVATION_EXECUTOR_BEAN_NAME, Executor.class, () -> Runnable::run)
            .withUserConfiguration(UiSimulatorConfiguration.class);

    @Test
    void registersRegistryReaperAndFacadeBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DemoRunConnectionRegistry.class);
            assertThat(context).hasSingleBean(DemoRunConnectionReaper.class);
            assertThat(context).hasSingleBean(LiveMapSimulatorRunFacade.class);
        });
    }
}
