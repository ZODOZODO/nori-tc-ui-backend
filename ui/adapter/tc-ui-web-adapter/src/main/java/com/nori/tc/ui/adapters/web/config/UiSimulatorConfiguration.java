package com.nori.tc.ui.adapters.web.config;

import com.nori.tc.ui.adapters.web.simulator.DemoRunConnectionReaper;
import com.nori.tc.ui.adapters.web.simulator.DemoRunConnectionRegistry;
import com.nori.tc.ui.adapters.web.simulator.HttpSimulatorOrchestratorClient;
import com.nori.tc.ui.adapters.web.simulator.LiveMapSimulatorRunFacade;
import com.nori.tc.ui.adapters.web.simulator.SimulatorModeMapper;
import com.nori.tc.ui.adapters.web.simulator.SimulatorOrchestratorClient;
import com.nori.tc.ui.adapters.web.simulator.SimulatorTargetAssembler;
import com.nori.tc.ui.core.service.DemoRunConnectionGate;
import com.nori.tc.ui.core.service.EqpManagementService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * 신규 simulator orchestrator(:18090) 연동 빈 조립(R-1).
 *
 * <p>{@code SimulatorOrchestratorClient} 는 조건부 빈을 회피해 <b>항상</b> 생성한다(@Import 정적, context-load 안정).
 * HttpClient 는 별도 빈으로 노출하지 않고 client 내부에서 inline 생성한다(타입 충돌 회피).
 */
@Configuration
@EnableConfigurationProperties(UiSimulatorProperties.class)
public class UiSimulatorConfiguration {

    @Bean
    public SimulatorOrchestratorClient simulatorOrchestratorClient(
            final ObjectMapper objectMapper,
            final UiSimulatorProperties properties
    ) {
        final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
        return new HttpSimulatorOrchestratorClient(httpClient, objectMapper, properties);
    }

    @Bean
    public SimulatorModeMapper simulatorModeMapper() {
        return new SimulatorModeMapper();
    }

    @Bean
    public SimulatorTargetAssembler simulatorTargetAssembler(final EqpManagementService eqpManagementService) {
        return new SimulatorTargetAssembler(eqpManagementService);
    }

    @Bean
    public DemoRunConnectionRegistry demoRunConnectionRegistry() {
        return new DemoRunConnectionRegistry();
    }

    @Bean
    public DemoRunConnectionReaper demoRunConnectionReaper(
            final SimulatorOrchestratorClient simulatorOrchestratorClient,
            final DemoRunConnectionGate demoRunConnectionGate,
            final DemoRunConnectionRegistry demoRunConnectionRegistry,
            final UiDualRequestProperties dualRequestProperties
    ) {
        return new DemoRunConnectionReaper(
                simulatorOrchestratorClient,
                demoRunConnectionGate,
                demoRunConnectionRegistry,
                dualRequestProperties);
    }

    @Bean
    public LiveMapSimulatorRunFacade liveMapSimulatorRunFacade(
            final SimulatorOrchestratorClient simulatorOrchestratorClient,
            final SimulatorModeMapper simulatorModeMapper,
            final SimulatorTargetAssembler simulatorTargetAssembler,
            final DemoRunConnectionGate demoRunConnectionGate,
            final UiDualRequestProperties dualRequestProperties,
            final DemoRunConnectionRegistry demoRunConnectionRegistry,
            @Qualifier(LiveMapSimulatorRunFacade.ACTIVATION_EXECUTOR_BEAN_NAME) final Executor activationExecutor,
            final UiSimulatorProperties uiSimulatorProperties
    ) {
        return new LiveMapSimulatorRunFacade(
                simulatorOrchestratorClient,
                simulatorModeMapper,
                simulatorTargetAssembler,
                demoRunConnectionGate,
                dualRequestProperties,
                demoRunConnectionRegistry,
                activationExecutor,
                uiSimulatorProperties.getConnectionConfirmTimeoutMs());
    }
}
