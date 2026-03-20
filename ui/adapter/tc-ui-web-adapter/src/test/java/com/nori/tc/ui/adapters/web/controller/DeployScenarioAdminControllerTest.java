package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.controller.support.UiApiExceptionHandler;
import com.nori.tc.ui.core.deploy.DeployScenarioCatalog;
import com.nori.tc.ui.core.deploy.DeployScenarioDefinition;
import com.nori.tc.ui.core.deploy.DeployScenarioValidationResult;
import com.nori.tc.ui.core.deploy.DeployTargetApp;
import com.nori.tc.ui.core.deploy.DeployScenarioAction;
import com.nori.tc.ui.core.deploy.DeployScenarioCondition;
import com.nori.tc.ui.core.deploy.DeployScenarioRetryPolicy;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.service.DeployScenarioCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeployScenarioAdminControllerTest {

    private DeployScenarioCatalogService deployScenarioCatalogService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        deployScenarioCatalogService = mock(DeployScenarioCatalogService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DeployScenarioAdminController(deployScenarioCatalogService))
                .setControllerAdvice(new UiApiExceptionHandler())
                .build();
    }

    @Test
    void validateReturnsWrappedSuccessResponse() throws Exception {
        when(deployScenarioCatalogService.validate(isNull(), anyString()))
                .thenReturn(new DeployScenarioValidationResult(
                        true,
                        OffsetDateTime.now(ZoneOffset.UTC),
                        3,
                        List.of()
                ));

        mockMvc.perform(post("/api/admin/deploy-scenarios/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.loadedScenarioCount").value(3));
    }

    @Test
    void reloadReturnsConflictWhenAnotherReloadIsRunning() throws Exception {
        when(deployScenarioCatalogService.reload(anyString(), anyString()))
                .thenThrow(new UiConflictException("scenario reload가 이미 진행 중입니다."));

        mockMvc.perform(post("/api/admin/deploy-scenarios/reload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "manual reload"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    void catalogReturnsCurrentScenarioSummaries() throws Exception {
        when(deployScenarioCatalogService.currentCatalog())
                .thenReturn(new DeployScenarioCatalog(
                        7,
                        "abc123",
                        OffsetDateTime.now(ZoneOffset.UTC),
                        "tester",
                        "MANUAL_RELOAD",
                        Map.of("EQP_START", scenarioDefinition())
                ));

        mockMvc.perform(get("/api/admin/deploy-scenarios/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.catalogVersion").value(7))
                .andExpect(jsonPath("$.data.scenarioCount").value(1))
                .andExpect(jsonPath("$.data.scenarios[0].code").value("EQP_START"));
    }

    private DeployScenarioDefinition scenarioDefinition() {
        return new DeployScenarioDefinition(
                1,
                new DeployScenarioDefinition.Scenario(
                        "EQP_START",
                        "EQP Start",
                        "Gateway runtime을 시작한다."
                ),
                new DeployScenarioDefinition.AppliesTo(
                        Set.of(UiCommandEventType.EQP_START),
                        DeployScenarioCondition.none()
                ),
                List.of(new DeployScenarioDefinition.Step(
                        10,
                        "START_GATEWAY_RUNTIME",
                        "Gateway runtime 시작",
                        DeployTargetApp.GATEWAY,
                        "설비 runtime을 시작한다.",
                        DeployScenarioCondition.none(),
                        new DeployScenarioDefinition.Run(
                                DeployScenarioAction.START_GATEWAY_RUNTIME,
                                30000L,
                                true,
                                new DeployScenarioRetryPolicy(1, 0L)
                        ),
                        DeployScenarioDefinition.Rollback.disabled()
                ))
        );
    }
}
