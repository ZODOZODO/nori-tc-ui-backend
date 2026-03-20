package com.nori.tc.ui.adapters.web.controller;

import tools.jackson.databind.ObjectMapper;
import com.nori.tc.ui.adapters.web.controller.support.UiApiExceptionHandler;
import com.nori.tc.ui.core.deploy.DeployCreateResult;
import com.nori.tc.ui.core.deploy.DeployExecutionPolicy;
import com.nori.tc.ui.core.deploy.DeployResultMessage;
import com.nori.tc.ui.core.deploy.DeployResultSeverity;
import com.nori.tc.ui.core.deploy.DeployStatus;
import com.nori.tc.ui.core.deploy.DeploySummaryView;
import com.nori.tc.ui.core.deploy.DeployTargetCounts;
import com.nori.tc.ui.core.deploy.DeployTargetPage;
import com.nori.tc.ui.core.deploy.DeployTargetSnapshot;
import com.nori.tc.ui.core.deploy.DeployTargetStatus;
import com.nori.tc.ui.core.service.DeployService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeployControllerTest {

    private DeployService deployService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        deployService = mock(DeployService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DeployController(deployService, new ObjectMapper()))
                .setControllerAdvice(new UiApiExceptionHandler())
                .build();
    }

    @Test
    void createReturnsAcceptedResponse() throws Exception {
        when(deployService.createDeploy(any(), anyString()))
                .thenReturn(new DeployCreateResult(
                        1001L,
                        DeployStatus.RUNNING,
                        "DEPLOY_ACCEPTED",
                        new DeployResultMessage(
                                "작업이 접수되었습니다.",
                                "설비 2대에 대한 작업이 등록되었습니다.",
                                DeployResultSeverity.INFO
                        )
                ));

        mockMvc.perform(post("/api/deploys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "deployName": "PHOTO Update",
                                  "eventType": "EQP_SEND_MESSAGE",
                                  "executionPolicy": "BEST_EFFORT",
                                  "commonRequest": {
                                    "interfaceType": "SOCKET",
                                    "uiMessage": {
                                      "messageName": "EQP_CONDITION_CHECK",
                                      "rawMessage": "CMD=CHECK"
                                    }
                                  },
                                  "targets": [
                                    {
                                      "eqpId": "PHOTO_001",
                                      "uiMessageOverride": {}
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.deployKey").value(1001))
                .andExpect(jsonPath("$.data.deployStatus").value("RUNNING"))
                .andExpect(jsonPath("$.data.resultCode").value("DEPLOY_ACCEPTED"))
                .andExpect(jsonPath("$.data.polling.summaryUrl").value("/api/deploys/1001"))
                .andExpect(jsonPath("$.data.polling.targetsUrl").value("/api/deploys/1001/targets"));
    }

    @Test
    void getSummaryReturnsPollingSnapshot() throws Exception {
        when(deployService.getDeploySummary(1001L))
                .thenReturn(new DeploySummaryView(
                        1001L,
                        "PHOTO Update",
                        com.nori.tc.ui.core.model.UiCommandEventType.EQP_UPDATE,
                        DeployStatus.RUNNING,
                        DeployExecutionPolicy.BEST_EFFORT,
                        OffsetDateTime.now(ZoneOffset.UTC),
                        OffsetDateTime.now(ZoneOffset.UTC),
                        null,
                        new DeployTargetCounts(10, 1, 0, 2, 7, 0, 0, 0, 0, 0),
                        false,
                        false,
                        "DEPLOY_RUNNING",
                        new DeployResultMessage(
                                "설비 변경 작업 진행 중",
                                "10대 중 7대 완료, 2대 진행 중입니다.",
                                DeployResultSeverity.INFO
                        )
                ));

        mockMvc.perform(get("/api/deploys/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.deployKey").value(1001))
                .andExpect(jsonPath("$.data.eventType").value("EQP_UPDATE"))
                .andExpect(jsonPath("$.data.deployStatus").value("RUNNING"))
                .andExpect(jsonPath("$.data.targetSummary.total").value(10))
                .andExpect(jsonPath("$.data.resultCode").value("DEPLOY_RUNNING"));
    }

    @Test
    void getTargetsReturnsPagedResult() throws Exception {
        when(deployService.getDeployTargets(1001L, 0, 20, "FAILED", "PHOTO"))
                .thenReturn(new DeployTargetPage(
                        1001L,
                        0,
                        20,
                        1L,
                        1,
                        List.of(new DeployTargetSnapshot(
                                2001L,
                                "PHOTO_001",
                                DeployTargetStatus.FAILED,
                                10,
                                "SEND_MESSAGE",
                                com.nori.tc.ui.core.deploy.DeployStepStatus.FAILED,
                                false,
                                "SEND_MESSAGE_FAILED",
                                "설비 메시지 송신에 실패했습니다.",
                                OffsetDateTime.now(ZoneOffset.UTC)
                        ))
                ));

        mockMvc.perform(get("/api/deploys/1001/targets")
                        .param("page", "0")
                        .param("size", "20")
                        .param("status", "FAILED")
                        .param("eqpId", "PHOTO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.targets[0].eqpId").value("PHOTO_001"))
                .andExpect(jsonPath("$.data.targets[0].targetStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.targets[0].currentStepCode").value("SEND_MESSAGE"))
                .andExpect(jsonPath("$.data.targets[0].lastErrorCode").value("SEND_MESSAGE_FAILED"));

        verify(deployService).getDeployTargets(1001L, 0, 20, "FAILED", "PHOTO");
    }
}
