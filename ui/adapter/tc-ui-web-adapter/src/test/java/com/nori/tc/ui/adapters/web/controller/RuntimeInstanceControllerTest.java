package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.core.port.redis.RuntimeInstanceQueryPort;
import com.nori.tc.ui.core.port.redis.RuntimeInstanceQueryPort.RuntimeInstanceView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RuntimeInstanceControllerTest {

    @Mock
    private RuntimeInstanceQueryPort runtimeInstanceQueryPort;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new RuntimeInstanceController(runtimeInstanceQueryPort)).build();
    }

    @Test
    @DisplayName("gateway 인스턴스 목록을 membership 필드 그대로 내려준다")
    void exposesGatewayInstances() throws Exception {
        when(runtimeInstanceQueryPort.findGatewayInstances()).thenReturn(List.of(
                new RuntimeInstanceView(
                        "tc-gateway:uid-a:boot-a", "gw-pod-a", "10.244.1.52", "ACTIVE_SYNCED", "1", 1000L, 2000L),
                new RuntimeInstanceView(
                        "tc-gateway:uid-b:boot-b", "gw-pod-b", null, "FENCED", "1", null, null)
        ));

        mockMvc.perform(get("/api/eqp/gateway-instances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].sessionId").value("tc-gateway:uid-a:boot-a"))
                .andExpect(jsonPath("$.data[0].podName").value("gw-pod-a"))
                .andExpect(jsonPath("$.data[0].podIp").value("10.244.1.52"))
                .andExpect(jsonPath("$.data[0].state").value("ACTIVE_SYNCED"))
                .andExpect(jsonPath("$.data[0].updatedAtEpochMs").value(2000L))
                // 상태는 그대로 실어 보낸다 — 대기 가능 여부 판정은 소비자가 한다.
                .andExpect(jsonPath("$.data[1].state").value("FENCED"))
                .andExpect(jsonPath("$.data[1].registeredAtEpochMs").isEmpty());
    }

    @Test
    @DisplayName("business 경로는 business 축을 조회하고 Pod IP 조인 키를 함께 내려준다")
    void exposesBusinessInstancesWithPodIp() throws Exception {
        when(runtimeInstanceQueryPort.findBusinessInstances()).thenReturn(List.of(
                new RuntimeInstanceView("tc-business:uid-a:boot-a", "tc-business-5b7fd78965-987d9",
                        "10.244.2.80", "ACTIVE_SYNCED", "1", 1000L, 2000L)
        ));

        mockMvc.perform(get("/api/eqp/business-instances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].podName").value("tc-business-5b7fd78965-987d9"))
                .andExpect(jsonPath("$.data[0].podIp").value("10.244.2.80"));
    }

    @Test
    @DisplayName("조정 원장을 쓰지 않는 구성은 빈 배열이다")
    void returnsEmptyArrayWhenNoInstanceIsKnown() throws Exception {
        when(runtimeInstanceQueryPort.findGatewayInstances()).thenReturn(List.of());

        mockMvc.perform(get("/api/eqp/gateway-instances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
