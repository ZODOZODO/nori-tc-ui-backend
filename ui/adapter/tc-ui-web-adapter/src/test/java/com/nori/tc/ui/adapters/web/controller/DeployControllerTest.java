package com.nori.tc.ui.adapters.web.controller;

import tools.jackson.databind.ObjectMapper;
import com.nori.tc.ui.adapters.web.controller.support.UiApiExceptionHandler;
import com.nori.tc.ui.core.deploy.DeployCreateResult;
import com.nori.tc.ui.core.deploy.DeployActionResult;
import com.nori.tc.ui.core.deploy.DeployDeleteResult;
import com.nori.tc.ui.core.deploy.DeployExecutionPolicy;
import com.nori.tc.ui.core.deploy.DeployHistoryView;
import com.nori.tc.ui.core.deploy.DeployRegisterTargetView;
import com.nori.tc.ui.core.deploy.DeployResultMessage;
import com.nori.tc.ui.core.deploy.DeployResultSeverity;
import com.nori.tc.ui.core.deploy.DeployStateOverview;
import com.nori.tc.ui.core.deploy.DeployStatus;
import com.nori.tc.ui.core.deploy.DeploySummaryView;
import com.nori.tc.ui.core.deploy.DeployTargetCounts;
import com.nori.tc.ui.core.deploy.DeployTargetPage;
import com.nori.tc.ui.core.deploy.DeployTargetSnapshot;
import com.nori.tc.ui.core.deploy.DeployTargetStatus;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.exception.UiErrorCode;
import com.nori.tc.ui.core.service.DeployPlanner;
import com.nori.tc.ui.core.service.DeployRegisterQueryService;
import com.nori.tc.ui.core.service.DeployScenarioCatalogService;
import com.nori.tc.ui.core.service.DeployService;
import com.nori.tc.ui.domain.common.model.UiModelStatus;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeployControllerTest {

    private DeployService deployService;
    private DeployPlanner deployPlanner;
    private DeployRegisterQueryService deployRegisterQueryService;
    private DeployScenarioCatalogService deployScenarioCatalogService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        deployService = mock(DeployService.class);
        deployPlanner = mock(DeployPlanner.class);
        deployRegisterQueryService = mock(DeployRegisterQueryService.class);
        deployScenarioCatalogService = mock(DeployScenarioCatalogService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new DeployController(
                                deployService,
                                deployPlanner,
                                deployRegisterQueryService,
                                deployScenarioCatalogService,
                                new ObjectMapper()
                        )
                )
                .setControllerAdvice(new UiApiExceptionHandler())
                .build();
    }

    @Test
    void previewRegisterScenarioReturnsRecommendedCandidates() throws Exception {
        when(deployPlanner.previewRegisterScenarioSelection(any()))
                .thenReturn(new com.nori.tc.ui.core.deploy.DeployRegisterScenarioPreviewResult(
                        List.of("PHOTO_003"),
                        List.of("PHOTO_001", "PHOTO_002"),
                        java.util.Set.of(
                                com.nori.tc.ui.core.deploy.DeployDeltaKind.MODEL_CHANGED,
                                com.nori.tc.ui.core.deploy.DeployDeltaKind.EQP_PARAM_CHANGED
                        ),
                        "REGISTER_MODEL_EQP_PARAM",
                        "감지된 변경 조합과 정확히 일치합니다.",
                        List.of(new com.nori.tc.ui.core.deploy.DeployRegisterScenarioPreviewResult.ScenarioCandidate(
                                "REGISTER_MODEL_EQP_PARAM",
                                "Model + Eqp Param 변경",
                                "Model 변경과 설비 파라미터 버전 반영을 함께 수행한다.",
                                1,
                                3,
                                java.util.Set.of(
                                        com.nori.tc.ui.core.deploy.DeployDeltaKind.MODEL_CHANGED,
                                        com.nori.tc.ui.core.deploy.DeployDeltaKind.EQP_PARAM_CHANGED
                                ),
                                true,
                                true,
                                "감지된 변경 조합과 정확히 일치합니다."
                        ))
                ));

        mockMvc.perform(post("/api/deploys/register/scenario-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "commonRequest": {
                                    "interfaceType": "SOCKET",
                                    "uiMessage": {
                                      "modelInfo": {
                                        "modelVersionKey": 1002
                                      }
                                    }
                                  },
                                  "targets": [
                                    {
                                      "eqpId": "PHOTO_001",
                                      "uiMessageOverride": {
                                        "eqpParams": {
                                          "version": "EQP_PARAM_V2"
                                        }
                                      }
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.removedEqpIds[0]").value("PHOTO_003"))
                .andExpect(jsonPath("$.data.recommendedScenarioCode").value("REGISTER_MODEL_EQP_PARAM"))
                .andExpect(jsonPath("$.data.scenarios[0].recommended").value(true))
                .andExpect(jsonPath("$.data.scenarios[0].code").value("REGISTER_MODEL_EQP_PARAM"));

        verify(deployPlanner).previewRegisterScenarioSelection(any());
    }

    @Test
    void getRegisterTargetsReturnsPagedResult() throws Exception {
        final UiPageRequest pageRequest = UiPageRequest.of(0, 50);
        when(deployRegisterQueryService.findRegisterTargets("PhotoCell-A", 2401L, "PHOTO", pageRequest))
                .thenReturn(PagedResponse.of(
                        List.of(new DeployRegisterTargetView(
                                101L,
                                "PHOTO-01",
                                UiProtocolType.SOCKET,
                                true,
                                2401L,
                                "PhotoCell-A",
                                "v2.4",
                                UiModelStatus.OPERATE,
                                9001L,
                                "photo-business-v2.jar",
                                "p2.4.1",
                                true,
                                7001L,
                                DeployStatus.RUNNING,
                                "PHOTO Active Deploy",
                                "ops-user"
                        )),
                        0,
                        50,
                        1L
                ));

        mockMvc.perform(get("/api/deploys/register/targets")
                        .param("modelName", "PhotoCell-A")
                        .param("modelVersionKey", "2401")
                        .param("eqpId", "PHOTO")
                        .param("offset", "0")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].eqpId").value("PHOTO-01"))
                .andExpect(jsonPath("$.data.items[0].commInterface").value("SOCKET"))
                .andExpect(jsonPath("$.data.items[0].modelStatus").value("OPERATE"))
                .andExpect(jsonPath("$.data.items[0].businessJarFileName").value("photo-business-v2.jar"))
                .andExpect(jsonPath("$.data.items[0].blockedByActiveDeploy").value(true))
                .andExpect(jsonPath("$.data.items[0].activeDeployKey").value(7001))
                .andExpect(jsonPath("$.data.items[0].activeDeployStatus").value("RUNNING"))
                .andExpect(jsonPath("$.data.items[0].activeDeployName").value("PHOTO Active Deploy"))
                .andExpect(jsonPath("$.data.items[0].activeRequestedBy").value("ops-user"))
                .andExpect(jsonPath("$.data.count").value(1));

        verify(deployRegisterQueryService).findRegisterTargets("PhotoCell-A", 2401L, "PHOTO", pageRequest);
    }

    @Test
    void getHistoryReturnsPagedResult() throws Exception {
        final UiPageRequest pageRequest = UiPageRequest.of(0, 20);
        final OffsetDateTime fromCreatedAt = OffsetDateTime.parse("2026-03-24T00:00:00Z");
        final OffsetDateTime toCreatedAtExclusive = OffsetDateTime.parse("2026-03-31T00:00:00Z");
        when(deployService.getDeployHistory(
                "PhotoCell-A",
                "ops-user",
                fromCreatedAt,
                toCreatedAtExclusive,
                true,
                pageRequest
        )).thenReturn(PagedResponse.of(
                List.of(new DeployHistoryView(
                        1001L,
                        "PhotoCell-A v2.4 Deploy",
                        com.nori.tc.ui.core.model.UiCommandEventType.EQP_UPDATE,
                        DeployStatus.COMPLETED,
                        false,
                        DeployExecutionPolicy.BEST_EFFORT,
                        "ops-user",
                        OffsetDateTime.parse("2026-03-30T08:00:00Z"),
                        OffsetDateTime.parse("2026-03-30T08:01:00Z"),
                        OffsetDateTime.parse("2026-03-30T08:02:30Z"),
                        true,
                        "SOCKET",
                        "PhotoCell-A",
                        "v2.4",
                        "photo-business-v2.jar",
                        new DeployTargetCounts(2, 0, 0, 0, 2, 0, 0, 0, 0, 0)
                )),
                0,
                20,
                1L
        ));

        mockMvc.perform(get("/api/deploys")
                        .param("modelName", "PhotoCell-A")
                        .param("requestedBy", "ops-user")
                        .param("fromDate", "2026-03-24")
                        .param("toDate", "2026-03-30")
                        .param("terminalOnly", "true")
                        .param("offset", "0")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].deployKey").value(1001))
                .andExpect(jsonPath("$.data.items[0].deployName").value("PhotoCell-A v2.4 Deploy"))
                .andExpect(jsonPath("$.data.items[0].requestedBy").value("ops-user"))
                .andExpect(jsonPath("$.data.items[0].terminal").value(true))
                .andExpect(jsonPath("$.data.items[0].interfaceType").value("SOCKET"))
                .andExpect(jsonPath("$.data.items[0].selectedBusinessJarName").value("photo-business-v2.jar"))
                .andExpect(jsonPath("$.data.items[0].targetSummary.completed").value(2))
                .andExpect(jsonPath("$.data.count").value(1));

        verify(deployService).getDeployHistory(
                "PhotoCell-A",
                "ops-user",
                fromCreatedAt,
                toCreatedAtExclusive,
                true,
                pageRequest
        );
    }

    @Test
    void getHistoryDefaultsToNonTerminalRowsWhenTerminalOnlyIsFalse() throws Exception {
        final UiPageRequest pageRequest = UiPageRequest.of(0, 20);
        when(deployService.getDeployHistory(
                null,
                null,
                null,
                null,
                false,
                pageRequest
        )).thenReturn(PagedResponse.of(
                List.of(new DeployHistoryView(
                        1002L,
                        "PhotoCell-A v2.5 Deploy",
                        com.nori.tc.ui.core.model.UiCommandEventType.EQP_UPDATE,
                        DeployStatus.RUNNING,
                        false,
                        DeployExecutionPolicy.BEST_EFFORT,
                        "ops-user",
                        OffsetDateTime.parse("2026-03-31T08:00:00Z"),
                        OffsetDateTime.parse("2026-03-31T08:01:00Z"),
                        null,
                        false,
                        "SOCKET",
                        "PhotoCell-A",
                        "v2.5",
                        "photo-business-v3.jar",
                        new DeployTargetCounts(2, 1, 0, 1, 0, 0, 0, 0, 0, 0)
                )),
                0,
                20,
                1L
        ));

        mockMvc.perform(get("/api/deploys")
                        .param("offset", "0")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].deployKey").value(1002))
                .andExpect(jsonPath("$.data.items[0].deployStatus").value("RUNNING"))
                .andExpect(jsonPath("$.data.items[0].terminal").value(false))
                .andExpect(jsonPath("$.data.count").value(1));

        verify(deployService).getDeployHistory(
                null,
                null,
                null,
                null,
                false,
                pageRequest
        );
    }

    @Test
    void createReturnsAcceptedResponse() throws Exception {
        when(deployService.createDeploy(any(), anyString()))
                .thenReturn(new DeployCreateResult(
                        1001L,
                        DeployStatus.READY,
                        "DEPLOY_CREATED",
                        new DeployResultMessage(
                                "Deploy가 생성되었습니다.",
                                "Deploy 탭에서 실행해 주세요.",
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
                .andExpect(jsonPath("$.data.deployStatus").value("READY"))
                .andExpect(jsonPath("$.data.resultCode").value("DEPLOY_CREATED"))
                .andExpect(jsonPath("$.data.polling.summaryUrl").value("/api/deploys/1001"))
                .andExpect(jsonPath("$.data.polling.targetsUrl").value("/api/deploys/1001/targets"));
    }

    @Test
    void createReturnsConflictResponseWhenEqpAlreadyActive() throws Exception {
        when(deployService.createDeploy(any(), anyString()))
                .thenThrow(new UiConflictException(
                        UiErrorCode.DEPLOY_ACTIVE_EQP_CONFLICT,
                        "이미 active Deploy에 포함된 설비입니다. eqpId=PHOTO_001, activeDeployKey=7001, activeDeployStatus=RUNNING"
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
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("DEPLOY_ACTIVE_EQP_CONFLICT"))
                .andExpect(jsonPath("$.errorMsg").value("이미 active Deploy에 포함된 설비입니다. eqpId=PHOTO_001, activeDeployKey=7001, activeDeployStatus=RUNNING"));
    }

    @Test
    void getSummaryReturnsPollingSnapshot() throws Exception {
        when(deployService.getDeploySummary(1001L))
                .thenReturn(new DeploySummaryView(
                        1001L,
                        "PHOTO Update",
                        com.nori.tc.ui.core.model.UiCommandEventType.EQP_UPDATE,
                        null,
                        new DeployStateOverview("SOCKET", "PHOTO_MODEL", "1.0.0", "photo-business.jar", "EQP_PARAM_V1"),
                        DeployStatus.RUNNING,
                        true,
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
                .andExpect(jsonPath("$.data.asIsState.modelName").value("PHOTO_MODEL"))
                .andExpect(jsonPath("$.data.deployStatus").value("RUNNING"))
                .andExpect(jsonPath("$.data.pauseRequested").value(true))
                .andExpect(jsonPath("$.data.targetSummary.total").value(10))
                .andExpect(jsonPath("$.data.resultCode").value("DEPLOY_RUNNING"));
    }

    @Test
    void cancelReturnsActionResponse() throws Exception {
        when(deployService.cancelDeploy(1001L, "SYSTEM"))
                .thenReturn(new DeployActionResult(
                        1001L,
                        DeployStatus.CANCELED,
                        false,
                        "DEPLOY_CANCELED",
                        new DeployResultMessage(
                                "Deploy가 취소되었습니다.",
                                "진행 중이던 Deploy를 취소했습니다.",
                                DeployResultSeverity.INFO
                        )
                ));

        mockMvc.perform(post("/api/deploys/1001/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.deployKey").value(1001))
                .andExpect(jsonPath("$.data.deployStatus").value("CANCELED"))
                .andExpect(jsonPath("$.data.pauseRequested").value(false))
                .andExpect(jsonPath("$.data.resultCode").value("DEPLOY_CANCELED"));

        verify(deployService).cancelDeploy(1001L, "SYSTEM");
    }

    @Test
    void redeployReturnsAcceptedCreateResponse() throws Exception {
        when(deployService.redeploy(1001L, "SYSTEM"))
                .thenReturn(new DeployCreateResult(
                        1101L,
                        DeployStatus.READY,
                        "DEPLOY_CREATED",
                        new DeployResultMessage(
                                "Deploy가 생성되었습니다.",
                                "Deploy 탭에서 실행해 주세요.",
                                DeployResultSeverity.INFO
                        )
                ));

        mockMvc.perform(post("/api/deploys/1001/redeploy"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.deployKey").value(1101))
                .andExpect(jsonPath("$.data.deployStatus").value("READY"))
                .andExpect(jsonPath("$.data.polling.summaryUrl").value("/api/deploys/1101"))
                .andExpect(jsonPath("$.data.polling.targetsUrl").value("/api/deploys/1101/targets"));

        verify(deployService).redeploy(1001L, "SYSTEM");
    }

    @Test
    void startReturnsActionResponse() throws Exception {
        when(deployService.startDeploy(1001L, "SYSTEM"))
                .thenReturn(new DeployActionResult(
                        1001L,
                        DeployStatus.RUNNING,
                        false,
                        "DEPLOY_STARTED",
                        new DeployResultMessage(
                                "Deploy가 시작되었습니다.",
                                "선택한 Deploy 실행을 시작했습니다.",
                                DeployResultSeverity.INFO
                        )
                ));

        mockMvc.perform(post("/api/deploys/1001/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.deployKey").value(1001))
                .andExpect(jsonPath("$.data.deployStatus").value("RUNNING"))
                .andExpect(jsonPath("$.data.pauseRequested").value(false))
                .andExpect(jsonPath("$.data.resultCode").value("DEPLOY_STARTED"));

        verify(deployService).startDeploy(1001L, "SYSTEM");
    }

    @Test
    void holdReturnsActionResponse() throws Exception {
        when(deployService.holdDeploy(1001L, "SYSTEM"))
                .thenReturn(new DeployActionResult(
                        1001L,
                        DeployStatus.RUNNING,
                        true,
                        "DEPLOY_HOLD_REQUESTED",
                        new DeployResultMessage(
                                "Hold 요청이 접수되었습니다.",
                                "현재 step 완료 후 Deploy가 대기 상태로 전환됩니다.",
                                DeployResultSeverity.INFO
                        )
                ));

        mockMvc.perform(post("/api/deploys/1001/hold"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.deployKey").value(1001))
                .andExpect(jsonPath("$.data.deployStatus").value("RUNNING"))
                .andExpect(jsonPath("$.data.pauseRequested").value(true))
                .andExpect(jsonPath("$.data.resultCode").value("DEPLOY_HOLD_REQUESTED"));

        verify(deployService).holdDeploy(1001L, "SYSTEM");
    }

    @Test
    void deleteReturnsActionResponse() throws Exception {
        when(deployService.deleteDeploy(1001L, "SYSTEM"))
                .thenReturn(new DeployDeleteResult(
                        1001L,
                        true,
                        "DEPLOY_DELETED",
                        new DeployResultMessage(
                                "Deploy 이력이 삭제되었습니다.",
                                "선택한 Deploy 이력을 목록에서 제거했습니다.",
                                DeployResultSeverity.INFO
                        )
                ));

        mockMvc.perform(delete("/api/deploys/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.deployKey").value(1001))
                .andExpect(jsonPath("$.data.deleted").value(true))
                .andExpect(jsonPath("$.data.resultCode").value("DEPLOY_DELETED"));

        verify(deployService).deleteDeploy(1001L, "SYSTEM");
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
