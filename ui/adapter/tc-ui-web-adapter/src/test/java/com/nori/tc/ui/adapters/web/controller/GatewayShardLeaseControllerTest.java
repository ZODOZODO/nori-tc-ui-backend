package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.core.port.redis.GatewayShardLeasePort;
import com.nori.tc.ui.core.port.redis.GatewayShardLeasePort.GatewayShardLeaseView;
import org.junit.jupiter.api.BeforeEach;
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
class GatewayShardLeaseControllerTest {

    @Mock
    private GatewayShardLeasePort gatewayShardLeasePort;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new GatewayShardLeaseController(gatewayShardLeasePort)).build();
    }

    @Test
    void returnsLeasesWithOwnerAndUnownedGroup() throws Exception {
        when(gatewayShardLeasePort.findAllLeases()).thenReturn(List.of(
                new GatewayShardLeaseView("group-1", List.of(0, 1), "pod-a-uuid-1"),
                new GatewayShardLeaseView("group-2", List.of(2, 3), null)
        ));

        mockMvc.perform(get("/api/eqp/gateway-shard-leases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].groupId").value("group-1"))
                .andExpect(jsonPath("$.data[0].ownedPartitions[0]").value(0))
                .andExpect(jsonPath("$.data[0].ownedPartitions[1]").value(1))
                .andExpect(jsonPath("$.data[0].ownerInstanceId").value("pod-a-uuid-1"))
                .andExpect(jsonPath("$.data[1].groupId").value("group-2"))
                .andExpect(jsonPath("$.data[1].ownerInstanceId").isEmpty());
    }

    @Test
    void emptyShardGroupConfigReturnsEmptyArray() throws Exception {
        when(gatewayShardLeasePort.findAllLeases()).thenReturn(List.of());

        mockMvc.perform(get("/api/eqp/gateway-shard-leases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
