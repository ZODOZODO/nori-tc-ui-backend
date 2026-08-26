package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.core.port.messaging.BusinessAppAssignmentPort;
import com.nori.tc.ui.core.port.messaging.BusinessAppAssignmentPort.BusinessAppAssignmentView;
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
class BusinessAppAssignmentControllerTest {

    @Mock
    private BusinessAppAssignmentPort businessAppAssignmentPort;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new BusinessAppAssignmentController(businessAppAssignmentPort))
                .build();
    }

    @Test
    void returnsMembersWithOwnedPartitions() throws Exception {
        when(businessAppAssignmentPort.findAllAssignments()).thenReturn(List.of(
                new BusinessAppAssignmentView("member-a", "10.244.2.80", List.of(0, 1, 2)),
                new BusinessAppAssignmentView("member-b", null, List.of(3, 4, 5))
        ));

        mockMvc.perform(get("/api/eqp/business-app-assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].memberId").value("member-a"))
                .andExpect(jsonPath("$.data[0].host").value("10.244.2.80"))
                .andExpect(jsonPath("$.data[0].ownedPartitions[0]").value(0))
                .andExpect(jsonPath("$.data[1].memberId").value("member-b"))
                .andExpect(jsonPath("$.data[1].host").isEmpty())
                .andExpect(jsonPath("$.data[1].ownedPartitions[2]").value(5));
    }

    @Test
    void noRunningProcessReturnsEmptyArray() throws Exception {
        when(businessAppAssignmentPort.findAllAssignments()).thenReturn(List.of());

        mockMvc.perform(get("/api/eqp/business-app-assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
