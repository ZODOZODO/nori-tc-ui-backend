package com.nori.tc.ui.adapters.web.config;

import com.nori.tc.ui.adapters.web.simulator.DemoRunConnectionReaper;
import com.nori.tc.ui.adapters.web.simulator.DemoRunConnectionRegistry;
import com.nori.tc.ui.adapters.web.simulator.LiveMapSimulatorRunFacade;
import com.nori.tc.ui.core.service.DemoRunConnectionGate;
import com.nori.tc.ui.core.service.EqpManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

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
