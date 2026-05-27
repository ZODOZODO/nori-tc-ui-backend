package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.controller.support.UiApiExceptionHandler;
import com.nori.tc.ui.core.port.redis.BusinessDlqPort;
import com.nori.tc.ui.core.port.redis.GatewayDlqPort;
import com.nori.tc.ui.core.service.EqpRepairTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/dlq/quarantine} 응답이 quarantine entry 의 5 도메인 필드
 * ({@code equipmentId}, {@code reasonCode}, {@code reasonMessage}, {@code quarantinedAt}, {@code ttlSeconds}) 를
 * 그대로 JSON 으로 노출하는지 검증합니다.
 *
 * <p>실제 도메인 클래스 {@code RedisQuarantineEntry} 는 {@code tc-db-domain} 에 위치하며
 * web-adapter 모듈의 의존 그래프에는 노출되지 않으므로 (헥사고날 단방향성),
 * 본 테스트는 동일 필드 셋을 가진 로컬 fixture record 를 사용하여 Jackson 직렬화 결과만 검증합니다.</p>
 */
class DlqControllerQuarantineTest {

    /**
     * 컨트롤러가 받는 {@code List<?>} 를 통해 노출되는 quarantine entry 의 직렬화 형태를
     * 모사하기 위한 fixture. 실제 도메인 클래스와 동일한 5 getter 표면을 갖습니다.
     */
    private record QuarantineEntryFixture(
            String equipmentId,
            String reasonCode,
            String reasonMessage,
            Instant quarantinedAt,
            Long ttlSeconds
    ) {
    }

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
                .setControllerAdvice(new UiApiExceptionHandler(UiControllerTestMessageSource.create()))
                .build();
    }

    @Test
    void listGatewayQuarantine_returnsEntryFields() throws Exception {
        final QuarantineEntryFixture entry = new QuarantineEntryFixture(
                "EQP-Q-01",
                "EXT_FAULT",
                "외부 통신 장애 누적",
                Instant.parse("2026-05-13T01:23:45Z"),
                3600L
        );
        doReturn(List.of(entry)).when(gatewayDlqPort).listAllQuarantine();

        mockMvc.perform(get("/api/dlq/quarantine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].equipmentId").value("EQP-Q-01"))
                .andExpect(jsonPath("$.data[0].reasonCode").value("EXT_FAULT"))
                .andExpect(jsonPath("$.data[0].reasonMessage").value("외부 통신 장애 누적"))
                .andExpect(jsonPath("$.data[0].quarantinedAt").isString())
                .andExpect(jsonPath("$.data[0].ttlSeconds").value(3600));

        verify(gatewayDlqPort).listAllQuarantine();
    }

    @Test
    void listGatewayQuarantine_returnsEmptyArrayWhenNoEntries() throws Exception {
        doReturn(List.of()).when(gatewayDlqPort).listAllQuarantine();

        mockMvc.perform(get("/api/dlq/quarantine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));

        verify(gatewayDlqPort).listAllQuarantine();
    }
}
