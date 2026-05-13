package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.controller.support.UiApiExceptionHandler;
import com.nori.tc.ui.core.port.redis.BusinessDlqPort;
import com.nori.tc.ui.core.port.redis.GatewayDlqPort;
import com.nori.tc.ui.core.service.EqpRepairTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DLQ_READ 권한 PREFIX 매칭 검증을 대체하는 라우팅 검증 테스트입니다.
 *
 * <p>운영 환경의 인가 규칙은 DB seed
 * ({@code postgres_insert_sample_data.sql:755}) 에 정의된 PREFIX 매칭에 의존합니다:
 * resource={@code /api/dlq}, match_type=PREFIX, http_method=GET. 따라서
 * 신규 2 endpoint 가 {@code /api/dlq/...} 경로로만 등록되면 별도 seed 변경 없이
 * 자동 커버됩니다.</p>
 *
 * <p>본 테스트는 두 endpoint 의 실제 URL 경로가 {@code /api/dlq} 로 시작하는지를
 * Spring MVC 라우팅을 통해 확인하여, 추후 누군가 {@code @GetMapping} 경로를
 * {@code /quarantine} → {@code /alt/quarantine} 같은 형태로 잘못 바꾸면
 * 즉시 테스트가 깨지도록 보호합니다.</p>
 *
 * <p>SecurityFilterChain / UiTokenAuthenticationFilter / UiApiPermissionCache 의
 * 통합 검증은 본 모듈의 기존 패턴 (Glob 확인 결과 부재) 을 도입하면 인프라
 * 의존이 과해지므로, 본 SPEC 범위에서는 URL prefix 보존 검증으로 축소합니다.
 * 통합 보안 테스트는 별 SPEC 에서 다룹니다.</p>
 */
class DlqControllerSecurityTest {

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
    void quarantineEndpointIsRoutedUnderDlqPrefix() throws Exception {
        doReturn(List.of()).when(gatewayDlqPort).listAllQuarantine();

        mockMvc.perform(get("/api/dlq/quarantine"))
                .andExpect(status().isOk());
    }

    @Test
    void repairTaskEndpointIsRoutedUnderDlqPrefix() throws Exception {
        when(eqpRepairTaskService.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/dlq/repair-task"))
                .andExpect(status().isOk());
    }

}
