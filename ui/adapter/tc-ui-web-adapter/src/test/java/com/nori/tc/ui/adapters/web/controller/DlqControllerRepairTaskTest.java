package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.controller.support.UiApiExceptionHandler;
import com.nori.tc.ui.core.eqp.EqpRepairAction;
import com.nori.tc.ui.core.eqp.EqpRepairTask;
import com.nori.tc.ui.core.port.redis.BusinessDlqPort;
import com.nori.tc.ui.core.port.redis.GatewayDlqPort;
import com.nori.tc.ui.core.service.EqpRepairTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/dlq/repair-task} 응답이 {@link EqpRepairTask} 의 7 도메인 필드를
 * {@code EqpRepairTaskResponse} 로 평면화하여 노출하는지 검증합니다.
 */
class DlqControllerRepairTaskTest {

    private GatewayDlqPort gatewayDlqPort;
    private BusinessDlqPort businessDlqPort;
    private EqpRepairTaskService eqpRepairTaskService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        gatewayDlqPort = mock(GatewayDlqPort.class);
        businessDlqPort = mock(BusinessDlqPort.class);
        eqpRepairTaskService = mock(EqpRepairTaskService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new DlqController(gatewayDlqPort, businessDlqPort, eqpRepairTaskService))
                .setControllerAdvice(new UiApiExceptionHandler())
                .build();
    }

    @Test
    void listRepairTasks_returnsTaskFields() throws Exception {
        final EqpRepairTask task = new EqpRepairTask(
                "EQP-R-01:EQP_UPDATE:RUNTIME_UPDATE",
                "EQP-R-01",
                "EQP_UPDATE",
                EqpRepairAction.RUNTIME_UPDATE,
                "ERR-RUNTIME-42",
                "런타임 업데이트 실패",
                OffsetDateTime.parse("2026-05-13T02:34:56Z").withOffsetSameInstant(ZoneOffset.UTC)
        );
        when(eqpRepairTaskService.findAll()).thenReturn(List.of(task));

        mockMvc.perform(get("/api/dlq/repair-task"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].taskId").value("EQP-R-01:EQP_UPDATE:RUNTIME_UPDATE"))
                .andExpect(jsonPath("$.data[0].eqpId").value("EQP-R-01"))
                .andExpect(jsonPath("$.data[0].commandType").value("EQP_UPDATE"))
                .andExpect(jsonPath("$.data[0].repairAction").value("RUNTIME_UPDATE"))
                .andExpect(jsonPath("$.data[0].errorCode").value("ERR-RUNTIME-42"))
                .andExpect(jsonPath("$.data[0].errorMessage").value("런타임 업데이트 실패"))
                .andExpect(jsonPath("$.data[0].createdAt").isString());

        verify(eqpRepairTaskService).findAll();
    }

    @Test
    void listRepairTasks_returnsEmptyArrayWhenNoTasks() throws Exception {
        when(eqpRepairTaskService.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/dlq/repair-task"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));

        verify(eqpRepairTaskService).findAll();
    }
}
