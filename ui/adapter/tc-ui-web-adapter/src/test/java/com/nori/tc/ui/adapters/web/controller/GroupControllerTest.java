package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.application.admin.GroupAdminFacade;
import com.nori.tc.ui.adapters.web.controller.support.UiApiExceptionHandler;
import com.nori.tc.ui.adapters.web.dto.response.GroupMemberResponse;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GroupControllerTest {

    private GroupAdminFacade groupAdminFacade;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        groupAdminFacade = mock(GroupAdminFacade.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new GroupController(groupAdminFacade))
                .setControllerAdvice(new UiApiExceptionHandler(UiControllerTestMessageSource.create()))
                .build();
    }

    @Test
    void listUsersByGroup_returnsPagedResult() throws Exception {
        when(groupAdminFacade.listUsersByGroupId(eq(11L), eq(UiPageRequest.of(0, 20))))
                .thenReturn(Optional.of(PagedResponse.of(
                        List.of(new GroupMemberResponse(101L, "alice", "Alice Park", "Nori", "Platform")),
                        0,
                        20,
                        1L
                )));

        mockMvc.perform(get("/api/group/11/user")
                        .param("offset", "0")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].userPk").value(101))
                .andExpect(jsonPath("$.data.items[0].userId").value("alice"))
                .andExpect(jsonPath("$.data.items[0].userName").value("Alice Park"))
                .andExpect(jsonPath("$.data.items[0].company").value("Nori"))
                .andExpect(jsonPath("$.data.items[0].department").value("Platform"))
                .andExpect(jsonPath("$.data.count").value(1));

        verify(groupAdminFacade).listUsersByGroupId(eq(11L), eq(UiPageRequest.of(0, 20)));
    }

    @Test
    void listUsersByGroup_returnsNotFoundWhenGroupMissing() throws Exception {
        when(groupAdminFacade.listUsersByGroupId(eq(999L), eq(UiPageRequest.of(0, 20))))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/group/999/user")
                        .param("offset", "0")
                        .param("limit", "20"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void listUsersByGroup_appliesDefaultPaging() throws Exception {
        when(groupAdminFacade.listUsersByGroupId(eq(11L), eq(UiPageRequest.of(0, 100))))
                .thenReturn(Optional.of(PagedResponse.empty(0, 100)));

        mockMvc.perform(get("/api/group/11/user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(groupAdminFacade).listUsersByGroupId(eq(11L), eq(UiPageRequest.of(0, 100)));
    }
}
