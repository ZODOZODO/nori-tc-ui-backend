package com.nori.tc.ui.adapters.web.config;

import com.nori.tc.ui.adapters.web.simulator.HttpSimulatorOrchestratorClient;
import com.nori.tc.ui.adapters.web.simulator.LiveMapSimulatorRunFacade;
import com.nori.tc.ui.adapters.web.simulator.SimulatorModeMapper;
import com.nori.tc.ui.adapters.web.simulator.SimulatorOrchestratorClient;
import com.nori.tc.ui.adapters.web.simulator.SimulatorTargetAssembler;
import com.nori.tc.ui.core.service.DemoRunConnectionGate;
import com.nori.tc.ui.core.service.EqpManagementService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 신규 simulator orchestrator(:18090) 연동 빈 조립(R-1). {@code UiDemoTesterConfiguration} 미러.
 *
 * <p>{@code SimulatorOrchestratorClient} 는 토글과 무관하게 <b>항상</b> 생성한다(조건부 빈 회피 → @Import 정적,
 * context-load 안정). 토글({@code tc.ui.simulator.enabled})은 facade/controller 가 검사. demo-tester HttpClient
 * 빈과의 타입 충돌(NoUniqueBean)을 피하려고 HttpClient 는 별도 빈으로 노출하지 않고 client 내부에서 inline 생성한다.
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
    public LiveMapSimulatorRunFacade liveMapSimulatorRunFacade(
            final UiSimulatorProperties properties,
            final SimulatorOrchestratorClient simulatorOrchestratorClient,
            final SimulatorModeMapper simulatorModeMapper,
            final SimulatorTargetAssembler simulatorTargetAssembler,
            final DemoRunConnectionGate demoRunConnectionGate,
            final UiDualRequestProperties dualRequestProperties
    ) {
        return new LiveMapSimulatorRunFacade(
                properties,
                simulatorOrchestratorClient,
                simulatorModeMapper,
                simulatorTargetAssembler,
                demoRunConnectionGate,
                dualRequestProperties);
    }
}
